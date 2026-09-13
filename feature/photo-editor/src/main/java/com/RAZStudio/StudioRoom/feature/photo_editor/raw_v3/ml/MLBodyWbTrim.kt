/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ML Intelligence — Per-Body WB Calibration Trim.
 *
 * Parses the bundled `body_wb_trim.json` asset and maps camera model
 * strings to per-body white balance and tint calibration offsets. These
 * offsets correct for known per-body color shifts measured across Canon
 * EOS bodies running Magic Lantern firmware.
 *
 * Data provenance: Empirical WB measurements across multiple Canon bodies,
 * validated against grey-card references under D50 illuminant.
 *
 * The table is loaded once on first access (double-checked locking) into a
 * list for substring matching. Unknown models return null (no trim applied).
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Per-camera-model WB/tint calibration offset lookup from `body_wb_trim.json`.
 *
 * Provides case-insensitive substring matching so that "Canon EOS 6D" matches
 * a query of "EOS 6D" or vice versa. Returns the first matching entry, or null
 * for unknown camera models.
 */
object MLBodyWbTrim {

    private const val TAG = "MLBodyWbTrim"
    private const val ASSET_PATH = "ml/body_wb_trim.json"

    @Volatile
    private var entries: List<BodyWbTrimEntry>? = null

    /**
     * Load the body WB trim table from app assets. Safe to call multiple times;
     * only the first call performs I/O (double-checked locking).
     */
    fun ensureLoaded(context: Context) {
        if (entries != null) return
        synchronized(this) {
            if (entries != null) return
            entries = parseAsset(context)
        }
    }

    /**
     * Look up WB/tint calibration offsets for the given camera model.
     *
     * Performs case-insensitive substring matching: a match is found if
     * either the query contains an entry's model string or the entry's
     * model string contains the query.
     *
     * @param cameraModel The camera model string from CR2 EXIF metadata.
     * @return The matching [BodyWbTrimEntry], or null if not found.
     */
    fun forModel(cameraModel: String): BodyWbTrimEntry? {
        val list = entries ?: return null
        if (cameraModel.isBlank()) return null

        val queryLower = cameraModel.lowercase()
        for (entry in list) {
            val entryLower = entry.cameraModel.lowercase()
            if (queryLower.contains(entryLower) || entryLower.contains(queryLower)) {
                return entry
            }
        }
        return null
    }

    /**
     * Parse the body_wb_trim.json asset file.
     *
     * Expected format:
     * ```json
     * {
     *   "version": 1,
     *   "bodies": [
     *     {"model": "Canon EOS 6D", "wbOffset": -3.0, "tintOffset": 1.5},
     *     ...
     *   ]
     * }
     * ```
     *
     * Returns an empty list if the file is missing or malformed.
     */
    private fun parseAsset(context: Context): List<BodyWbTrimEntry> {
        val result = mutableListOf<BodyWbTrimEntry>()
        try {
            val jsonStr = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val bodies = root.optJSONArray("bodies") ?: run {
                Log.w(TAG, "No 'bodies' array in $ASSET_PATH")
                return emptyList()
            }

            for (i in 0 until bodies.length()) {
                val obj = bodies.optJSONObject(i) ?: continue
                val model = obj.optString("model", "").trim()
                if (model.isEmpty()) continue

                val wbOffset = obj.optDouble("wbOffset", 0.0).toFloat()
                val tintOffset = obj.optDouble("tintOffset", 0.0).toFloat()

                result.add(
                    BodyWbTrimEntry(
                        cameraModel = model,
                        wbOffset = wbOffset.coerceIn(-20f, 20f),
                        tintOffset = tintOffset.coerceIn(-10f, 10f),
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_PATH: ${e.message}")
        }

        Log.d(TAG, "Loaded ${result.size} body WB trim entries")
        return result
    }
}
