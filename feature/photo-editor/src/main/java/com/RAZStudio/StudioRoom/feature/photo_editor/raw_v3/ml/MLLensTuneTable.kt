/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Lens-Aware Picture Style Trims.
 *
 * Parses the bundled `lens_tune.tbl` asset (pipe-delimited) and maps
 * Canon lens IDs (from CR2 EXIF) to per-lens finishing trims. These trims
 * adjust contrast, saturation, color tone, EV bias, and white-balance
 * scales to produce a more pleasing rendering out-of-the-box.
 *
 * Data provenance: Magic Lantern project sensor/lens characterization,
 * validated on Canon EOS bodies running ML firmware (DIGIC 4/5/6).
 *
 * The table is loaded once at app startup into a HashMap for O(1) lookup.
 * Unknown lenses fall back to lens_id 0 (neutral — no adjustments).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import android.content.Context
import android.util.Log

/**
 * A single row from lens_tune.tbl representing per-lens finishing trims.
 *
 * @property lensId Canon lens_id from EXIF (LibRaw idata.lens.id)
 * @property contrastBias Picture style contrast bias [-4..+4]
 * @property saturationBias Picture style saturation bias [-4..+4]
 * @property colorToneBias Picture style color tone bias [-4..+4], maps to tint offset
 * @property evBias Exposure bias in 1/8 EV steps [-3..+1]
 * @property wbRedScale White balance red scale (1024 = 1.0x, < 1024 warms)
 * @property wbBlueScale White balance blue scale (1024 = 1.0x, > 1024 warms)
 * @property caStrength Chromatic aberration correction strength [0..100], 0 = no CA data
 * @property fringeReduce Purple/green fringe suppression strength [0..100], 0 = no fringe data
 */
data class LensTuneEntry(
    val lensId: Int,
    val contrastBias: Float,
    val saturationBias: Float,
    val colorToneBias: Float,
    val evBias: Float,
    val wbRedScale: Float,
    val wbBlueScale: Float,
    val caStrength: Float = 0f,
    val fringeReduce: Float = 0f,
)

/**
 * Embedded lookup table mapping Canon lens IDs to picture-style trims.
 *
 * Loaded once from `assets/ml/lens_tune.tbl` at app startup. Provides O(1)
 * lookup by lens_id with fallback to lens_id 0 (neutral) for unknown lenses.
 */
object MLLensTuneTable {

    private const val TAG = "MLLensTuneTable"
    private const val ASSET_PATH = "ml/lens_tune.tbl"

    /** Neutral entry returned when lens_id is not found and no id-0 row exists. */
    private val NEUTRAL = LensTuneEntry(
        lensId = 0,
        contrastBias = 0f,
        saturationBias = 0f,
        colorToneBias = 0f,
        evBias = 0f,
        wbRedScale = 1024f,
        wbBlueScale = 1024f,
    )

    @Volatile
    private var table: HashMap<Int, LensTuneEntry>? = null

    /**
     * Load the lens tune table from app assets. Safe to call multiple times;
     * only the first call performs I/O.
     */
    fun ensureLoaded(context: Context) {
        if (table != null) return
        synchronized(this) {
            if (table != null) return
            table = parseAsset(context)
        }
    }

    /**
     * Look up finishing trims for the given Canon lens ID.
     *
     * @param lensId The Canon lens_id from CR2 EXIF metadata.
     * @return The matching [LensTuneEntry], or fallback to lens_id 0 (neutral).
     */
    fun forLensId(lensId: Int): LensTuneEntry {
        val map = table ?: return NEUTRAL
        return map[lensId] ?: map[0] ?: NEUTRAL
    }

    /**
     * Look up the raw entry for the given Canon lens ID without fallback.
     *
     * Unlike [forLensId], this returns null when the lens is not found in the
     * table rather than falling back to the neutral entry. Used by modules that
     * need to distinguish "unknown lens" from "lens with zero values".
     *
     * @param lensId The Canon lens_id from CR2 EXIF metadata.
     * @return The matching [LensTuneEntry], or null if not found.
     */
    fun findEntry(lensId: Int): LensTuneEntry? {
        val map = table ?: return null
        return map[lensId]
    }

    /**
     * Parse the pipe-delimited lens_tune.tbl from assets.
     *
     * Supports three row formats:
     * - 9-column (extended): `lens_id|contrast|saturation|color_tone|ev_bias|wb_r|wb_b|ca_strength|fringe_reduce`
     * - 7-column (standard): `lens_id|contrast|saturation|color_tone|ev_bias|wb_r|wb_b`
     * - 4-column (short): `lens_id|contrast|saturation|color_tone`
     *
     * Lines starting with `#` are comments (including `#user` annotations).
     * Blank lines are skipped.
     */
    private fun parseAsset(context: Context): HashMap<Int, LensTuneEntry> {
        val result = HashMap<Int, LensTuneEntry>()
        try {
            context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith('#')) continue

                    val parts = trimmed.split('|')
                    val entry = when {
                        parts.size >= 9 -> parseExtendedRow(parts)
                        parts.size >= 7 -> parseFullRow(parts)
                        parts.size >= 4 -> parseShortRow(parts)
                        else -> {
                            Log.w(TAG, "Skipping malformed line: $trimmed")
                            null
                        }
                    }
                    if (entry != null) {
                        result[entry.lensId] = entry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_PATH: ${e.message}")
        }
        if (result.isEmpty()) {
            result[0] = NEUTRAL
        }
        Log.d(TAG, "Loaded ${result.size} lens entries")
        return result
    }

    /** Parse a full 7-column row: lens_id|contrast|saturation|color_tone|ev_bias|wb_r|wb_b */
    private fun parseFullRow(parts: List<String>): LensTuneEntry? {
        return try {
            LensTuneEntry(
                lensId = parts[0].trim().toInt(),
                contrastBias = parts[1].trim().toFloat(),
                saturationBias = parts[2].trim().toFloat(),
                colorToneBias = parts[3].trim().toFloat(),
                evBias = parts[4].trim().toFloat(),
                wbRedScale = parts[5].trim().toFloat(),
                wbBlueScale = parts[6].trim().toFloat(),
            )
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number parse error: ${e.message}")
            null
        }
    }

    /** Parse a 9-column extended row: lens_id|contrast|saturation|color_tone|ev_bias|wb_r|wb_b|ca_strength|fringe_reduce */
    private fun parseExtendedRow(parts: List<String>): LensTuneEntry? {
        return try {
            LensTuneEntry(
                lensId = parts[0].trim().toInt(),
                contrastBias = parts[1].trim().toFloat(),
                saturationBias = parts[2].trim().toFloat(),
                colorToneBias = parts[3].trim().toFloat(),
                evBias = parts[4].trim().toFloat(),
                wbRedScale = parts[5].trim().toFloat(),
                wbBlueScale = parts[6].trim().toFloat(),
                caStrength = parts[7].trim().toFloat().coerceIn(0f, 100f),
                fringeReduce = parts[8].trim().toFloat().coerceIn(0f, 100f),
            )
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number parse error: ${e.message}")
            null
        }
    }

    /** Parse a short 4-column row: lens_id|contrast|saturation|color_tone */
    private fun parseShortRow(parts: List<String>): LensTuneEntry? {
        return try {
            LensTuneEntry(
                lensId = parts[0].trim().toInt(),
                contrastBias = parts[1].trim().toFloat(),
                saturationBias = parts[2].trim().toFloat(),
                colorToneBias = parts[3].trim().toFloat(),
                evBias = 0f,
                wbRedScale = 1024f,
                wbBlueScale = 1024f,
            )
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number parse error: ${e.message}")
            null
        }
    }
}
