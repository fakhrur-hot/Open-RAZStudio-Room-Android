/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import android.content.Context
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceBitDepth
import java.io.File

/**
 * Persists up to [MAX_PRESETS] named action-list presets to app-private storage.
 *
 * Each preset is stored as an XML file in `<filesDir>/raw_presets/preset_<index>.xml`
 * and a companion index file `<filesDir>/raw_presets/index.txt` that maps each slot
 * to its display name (one "name|filename" line per preset, order = slot order).
 *
 * Presets survive app close and restart.  Mask-specific fields are stripped when
 * saving (same behaviour as the file-export path) so presets are photo-agnostic.
 */
object RawPresetsStorage {

    const val MAX_PRESETS = 40

    /** Display names of the retired sinanonur film-sim factory presets. */
    private val FS_RETIRED_NAMES = setOf(
        "kodak portra 400",
        "fuji superia 400",
        "kodak ektar 100",
        "fuji velvia 50",
        "ilford hp5 plus",
        "kodak tri-x 400",
        "fuji pro 400h",
        "kodak gold 200",
        "fuji provia 100f",
        "kodak ektachrome e100",
        "lomography color negative 400",
        "cinestill 800t",
    )

    data class Preset(
        val name: String,
        val fileName: String,
        /** Workspace bit depth at the time the preset was saved. Defaults to BIT_8 for legacy entries. */
        val bitDepth: WorkspaceBitDepth = WorkspaceBitDepth.BIT_8,
    )

    // ── Directory helpers ─────────────────────────────────────────────────────

    private fun presetsDir(context: Context): File =
        File(context.filesDir, "raw_presets").also { it.mkdirs() }

    private fun indexFile(context: Context): File =
        File(presetsDir(context), "index.txt")

    /** Historical single marker from the razdream-only era — its presence means
     *  "razdream.xml has been seeded" and must keep suppressing that one. */
    private fun legacySeedMarkerFile(context: Context): File =
        File(presetsDir(context), ".defaults_seeded")

    /** Per-asset marker: written once `assets/presets/<assetFile>` has been
     *  seeded. Its presence — NOT the presence of the preset itself — gates
     *  re-seeding, so a user who deletes a factory preset never has it
     *  reappear, while a NEW bundled preset in an app update still seeds once. */
    private fun seedMarkerFile(context: Context, assetFile: String): File =
        File(presetsDir(context), ".seeded_$assetFile")

    // ── Bundled factory presets ───────────────────────────────────────────────

    /** True while [seedDefaultsIfNeeded] is running, so the [loadIndex] calls
     *  that [savePreset] makes internally don't re-enter the seeding loop —
     *  a stale-snapshot re-entry would clobber presets seeded moments earlier. */
    @Volatile private var seedingInProgress = false

    /**
     * Seed every bundled factory preset (see [BundledPresets]) exactly once
     * each — on a fresh install, and once per NEW bundled preset after an APK
     * upgrade. Per-asset markers gate re-seeding; each marker is written
     * BEFORE its [savePreset] so a crash mid-seed can only skip, never loop.
     *
     * Duplicate protection for the authoring device (whose user presets ARE
     * the bundled ones): a preset whose serialized content already exists in
     * the index — under any name — is marked seeded without saving a copy.
     */
    @Synchronized
    fun seedDefaultsIfNeeded(context: Context) {
        if (seedingInProgress) return
        seedingInProgress = true
        try {
            val entries = BundledPresets.list(context)
            for (e in entries) {
                val marker = seedMarkerFile(context, e.assetFile)
                if (marker.exists()) continue
                if (e.assetFile == BundledPresets.LEGACY_SEEDED_ASSET &&
                    legacySeedMarkerFile(context).exists()
                ) {
                    runCatching { marker.writeText("1") }
                    continue
                }
                runCatching { marker.writeText("1") }
                runCatching {
                    val actions = BundledPresets.load(context, e.assetFile) ?: return@runCatching
                    if (actions.isEmpty()) return@runCatching
                    val duplicate = matchingPresetIndex(context, actions) != null ||
                        loadIndex(context).any { it.name.equals(e.name, ignoreCase = true) }
                    if (!duplicate) savePreset(context, e.name, actions, e.bitDepth)
                }
            }
        } finally {
            seedingInProgress = false
        }
    }

    // ── Index read/write ──────────────────────────────────────────────────────

    fun loadIndex(context: Context): List<Preset> {
        seedDefaultsIfNeeded(context)
        retireFsPresetsIfNeeded(context)
        return readIndexOrRecover(context)
    }

    /**
     * One-time purge of the abandoned sinanonur/film-simulation factory presets
     * that may already have been seeded into `<filesDir>/raw_presets/`. Gated by
     * `.retired_fs_presets_v1` so it never re-deletes user-renamed copies later.
     * Only removes exact display-name matches + `.seeded_fs_*` markers.
     */
    @Synchronized
    private fun retireFsPresetsIfNeeded(context: Context) {
        val done = File(presetsDir(context), ".retired_fs_presets_v1")
        if (done.exists()) return
        runCatching {
            val dir = presetsDir(context)
            dir.listFiles { f -> f.isFile && f.name.startsWith(".seeded_fs_") }
                ?.forEach { it.delete() }
            val current = readIndexOrRecover(context)
            val kept = current.filterNot { it.name.lowercase() in FS_RETIRED_NAMES }
            if (kept.size != current.size) {
                current.filter { it.name.lowercase() in FS_RETIRED_NAMES }.forEach { p ->
                    File(dir, p.fileName).delete()
                }
                saveIndex(context, kept)
            }
        }
        runCatching { done.writeText("1") }
    }

    private fun readIndexOrRecover(context: Context): List<Preset> =
        runCatching {
            val f = indexFile(context)
            val fromIndex = if (f.exists()) {
                f.readLines()
                    .filter { it.contains('|') }
                    .map { line ->
                        val parts = line.split('|')
                        val name     = parts.getOrNull(0).orEmpty()
                        val fileName = parts.getOrNull(1).orEmpty()
                        val bitDepth = parts.getOrNull(2)
                            ?.let { tag ->
                                WorkspaceBitDepth.entries.firstOrNull { it.name == tag }
                            }
                            ?: WorkspaceBitDepth.BIT_8
                        Preset(name = name, fileName = fileName, bitDepth = bitDepth)
                    }
            } else emptyList()
            // Self-heal: if the index is missing/empty/stale but preset XML files
            // exist on disk, rebuild the index from them (recovers from a
            // truncated index.txt without losing the user's saved presets).
            val indexed = fromIndex.map { it.fileName }.toSet()
            val orphans = presetsDir(context).listFiles { file ->
                file.isFile && file.name.startsWith("preset_") &&
                    file.name.endsWith(".xml") && file.name !in indexed
            }?.sortedBy { it.name } ?: emptyList()
            if (orphans.isEmpty()) {
                fromIndex
            } else {
                val recovered = fromIndex + orphans.map { file ->
                    Preset(name = file.nameWithoutExtension, fileName = file.name)
                }
                runCatching { saveIndex(context, recovered) }   // persist the repair
                recovered
            }
        }.getOrDefault(emptyList())

    private fun saveIndex(context: Context, presets: List<Preset>) {
        indexFile(context).writeText(
            presets.joinToString("\n") { "${it.name}|${it.fileName}|${it.bitDepth.name}" }
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Save [actions] as a new preset named [name].
     * Returns false if [MAX_PRESETS] is already reached.
     */
    fun savePreset(
        context: Context,
        name: String,
        actions: List<RawAction>,
        bitDepth: WorkspaceBitDepth = WorkspaceBitDepth.BIT_8,
    ): Boolean {
        val current = loadIndex(context).toMutableList()
        if (current.size >= MAX_PRESETS) return false

        val sanitizedName = name.trim().take(40).ifEmpty { "Preset ${current.size + 1}" }
        val fileName = "preset_${System.currentTimeMillis()}.xml"
        val xmlFile = File(presetsDir(context), fileName)
        xmlFile.writeText(
            RawActionSerializer.serialize(actions, stripMaskFields = true)
        )
        current.add(Preset(sanitizedName, fileName, bitDepth))
        saveIndex(context, current)
        return true
    }

    /**
     * Load actions from the preset at [index] (0-based).
     * Returns null if the index is out of range or the file is unreadable.
     */
    fun loadPreset(context: Context, index: Int): List<RawAction>? =
        runCatching {
            val preset = loadIndex(context).getOrNull(index) ?: return null
            val file = File(presetsDir(context), preset.fileName)
            if (!file.exists()) return null
            RawActionSerializer.deserialize(file.readText()).takeIf { it.isNotEmpty() }
        }.getOrNull()

    /**
     * Index of the saved preset whose stored actions exactly match [actions]
     * (serialized identically to how [savePreset] writes them), or null if none.
     *
     * Best-effort: AUTO-EXPOSURE cards carry per-photo deltas, so a preset that
     * was saved on a different photo won't byte-match after re-resolution — that
     * correctly falls back to "no match" (i.e. Current/unsaved).
     */
    fun matchingPresetIndex(context: Context, actions: List<RawAction>): Int? {
        val currentBlob = runCatching {
            RawActionSerializer.serialize(actions, stripMaskFields = true).trim()
        }.getOrNull() ?: return null
        loadIndex(context).forEachIndexed { index, preset ->
            val file = File(presetsDir(context), preset.fileName)
            val blob = runCatching { if (file.exists()) file.readText().trim() else null }.getOrNull()
            if (blob != null && blob == currentBlob) return index
        }
        return null
    }

    /**
     * Delete the preset at [index]. Removes both the XML file and the index entry.
     */
    fun deletePreset(context: Context, index: Int) {
        val current = loadIndex(context).toMutableList()
        val preset = current.getOrNull(index) ?: return
        File(presetsDir(context), preset.fileName).delete()
        current.removeAt(index)
        saveIndex(context, current)
    }
}
