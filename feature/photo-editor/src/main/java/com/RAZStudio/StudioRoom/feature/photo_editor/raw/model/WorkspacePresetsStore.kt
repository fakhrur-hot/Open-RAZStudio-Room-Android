/*
 * StudioRoom — workspace presets store. Apache-2.0.
 *
 * A WorkspaceConfig already persists to SharedPreferences as the *current*
 * config. Presets sit alongside, in a separate prefs file, as a small JSON
 * map of name → flat key/value object. Each preset captures the same fields
 * `WorkspaceConfig.saveToPrefs` writes; on load we re-hydrate by name and
 * apply via `WorkspaceConfig.fromJson`.
 *
 * Storage format (one JSON string per preset, keyed by preset name):
 *   raw_workspace_presets / "<name>" → JSONObject({ bitDepth: "...", ... })
 *
 * Names are user-supplied strings; collisions overwrite without warning
 * (matches the user-mental model of "Save = replace").
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object WorkspacePresetsStore {

    const val PREFS_NAME = "raw_workspace_presets"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Names of all stored presets, in insertion order. */
    fun listNames(ctx: Context): List<String> = prefs(ctx).all.keys.toList()

    /** Save the given config under [name]. Overwrites if [name] exists. */
    fun save(ctx: Context, name: String, cfg: WorkspaceConfig) {
        if (name.isBlank()) return
        prefs(ctx).edit().putString(name, configToJson(cfg).toString()).apply()
    }

    /** Load preset [name] or return null if missing / malformed. */
    fun load(ctx: Context, name: String): WorkspaceConfig? {
        val raw = prefs(ctx).getString(name, null) ?: return null
        return runCatching { jsonToConfig(JSONObject(raw)) }.getOrNull()
    }

    /** Delete preset [name]. No-op if missing. */
    fun delete(ctx: Context, name: String) {
        prefs(ctx).edit().remove(name).apply()
    }

    // ── JSON conversion. Mirrors WorkspaceConfig.saveToPrefs key for key.
    //    Adding a new field to WorkspaceConfig requires adding it here too
    //    (and to fromPrefs in WorkspaceConfig.kt). Forgotten fields fall
    //    back to WorkspaceConfig.Default at load time. ──────────────────

    private fun configToJson(c: WorkspaceConfig): JSONObject = JSONObject().apply {
        put("bitDepth", c.bitDepth.name)
        put("colorGamut", c.colorGamut.name)
        put("demosaicAlgorithm", c.demosaicAlgorithm.name)
        put("highlightRecovery", c.highlightRecovery.name)
        put("nrEnabled", c.nrEnabled)
        put("nrLuma", c.nrLuma)
        put("nrChroma", c.nrChroma)
        put("dcpProfileId", c.dcpProfileId)
        put("outputBitDepthMode", c.outputBitDepthMode.name)
        put("sidecarEnabled", c.sidecarEnabled)
        put("caCorrectionEnabled", c.caCorrectionEnabled)
        put("wbSourceOrdinal", c.wbSourceOrdinal)
        put("exposureShiftEv", c.exposureShiftEv.toDouble())
        put("fbddNoise", c.fbddNoise)
        put("subjectDetectionEnabled", c.subjectDetectionEnabled)
        put("cameraStyleFinishEnabled", c.cameraStyleFinishEnabled)
        put("blackLevelDelta", c.blackLevelDelta.toDouble())
        put("whiteLevelDelta", c.whiteLevelDelta.toDouble())
        put("clipThreshold", c.clipThreshold.toDouble())
        put("dualContrastThreshold", c.dualContrastThreshold.toDouble())
        put("dualAutoContrast", c.dualAutoContrast)
        put("smartDefaultsEnabled", c.smartDefaultsEnabled)
    }

    private fun jsonToConfig(j: JSONObject): WorkspaceConfig {
        val d = WorkspaceConfig.Default
        fun str(k: String, def: String): String = j.optString(k, def)
        fun bool(k: String, def: Boolean): Boolean = j.optBoolean(k, def)
        fun int(k: String, def: Int): Int = j.optInt(k, def)
        fun flt(k: String, def: Float): Float = j.optDouble(k, def.toDouble()).toFloat()
        return WorkspaceConfig(
            bitDepth = runCatching { WorkspaceBitDepth.valueOf(str("bitDepth", d.bitDepth.name)) }
                .getOrDefault(d.bitDepth),
            colorGamut = runCatching { RawColorSpace.valueOf(str("colorGamut", d.colorGamut.name)) }
                .getOrDefault(d.colorGamut),
            demosaicAlgorithm = runCatching { DemosaicAlgorithm.valueOf(str("demosaicAlgorithm", d.demosaicAlgorithm.name)) }
                .getOrDefault(d.demosaicAlgorithm),
            highlightRecovery = runCatching { HighlightRecoveryMode.valueOf(str("highlightRecovery", d.highlightRecovery.name)) }
                .getOrDefault(d.highlightRecovery),
            nrEnabled = bool("nrEnabled", d.nrEnabled),
            nrLuma = int("nrLuma", d.nrLuma).coerceIn(0, 100),
            nrChroma = int("nrChroma", d.nrChroma).coerceIn(0, 100),
            dcpProfileId = str("dcpProfileId", d.dcpProfileId),
            outputBitDepthMode = runCatching { OutputBitDepthMode.valueOf(str("outputBitDepthMode", d.outputBitDepthMode.name)) }
                .getOrDefault(d.outputBitDepthMode),
            sidecarEnabled = bool("sidecarEnabled", d.sidecarEnabled),
            caCorrectionEnabled = bool("caCorrectionEnabled", d.caCorrectionEnabled),
            wbSourceOrdinal = int("wbSourceOrdinal", d.wbSourceOrdinal).coerceIn(0, 2),
            exposureShiftEv = flt("exposureShiftEv", d.exposureShiftEv).coerceIn(-2f, 3f),
            fbddNoise = int("fbddNoise", d.fbddNoise).coerceIn(0, 2),
            subjectDetectionEnabled = bool("subjectDetectionEnabled", d.subjectDetectionEnabled),
            cameraStyleFinishEnabled = bool("cameraStyleFinishEnabled", d.cameraStyleFinishEnabled),
            blackLevelDelta = flt("blackLevelDelta", d.blackLevelDelta),
            whiteLevelDelta = flt("whiteLevelDelta", d.whiteLevelDelta),
            clipThreshold = flt("clipThreshold", d.clipThreshold).coerceIn(0.80f, 1.00f),
            dualContrastThreshold = flt("dualContrastThreshold", d.dualContrastThreshold).coerceIn(0f, 1f),
            dualAutoContrast = bool("dualAutoContrast", d.dualAutoContrast),
            smartDefaultsEnabled = bool("smartDefaultsEnabled", d.smartDefaultsEnabled),
        )
    }
}
