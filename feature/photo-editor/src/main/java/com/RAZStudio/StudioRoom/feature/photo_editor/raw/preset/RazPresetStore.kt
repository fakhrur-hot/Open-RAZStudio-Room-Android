/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.preset

import android.content.Context
import android.util.Log
import java.io.File

/**
 * App-private preset file storage. Presets live at
 * `<filesDir>/presets/<slug>.razpreset` so they survive process death,
 * roam with the user via Backup & Restore, and don't require any
 * external-storage permission.
 *
 * The store is intentionally synchronous and tiny — presets are small
 * (~20-50 KB XML) and the user only saves/loads them at explicit moments,
 * not on every keystroke.
 */
object RazPresetStore {
    private const val TAG = "RazPresetStore"

    private fun dir(context: Context): File {
        val d = File(context.filesDir, RazPreset.PRESET_DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileFor(context: Context, name: String): File {
        // Slugify so a user-typed name doesn't break the filesystem.
        val slug = name.trim()
            .replace(Regex("[^A-Za-z0-9_\\- ]+"), "")
            .replace(Regex("\\s+"), "_")
            .ifBlank { "preset" }
            .take(64)
        return File(dir(context), "$slug.${RazPreset.FILE_EXTENSION}")
    }

    /** List all presets, newest first. */
    fun list(context: Context): List<RazPresetEntry> {
        return dir(context).listFiles { f ->
            f.isFile && f.extension == RazPreset.FILE_EXTENSION
        }?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                RazPresetEntry(
                    file = f,
                    displayName = f.nameWithoutExtension.replace('_', ' '),
                    sizeBytes = f.length(),
                    lastModifiedEpochMs = f.lastModified(),
                )
            }.orEmpty()
    }

    /** Save (overwrite if exists). Returns the written file on success. */
    fun save(context: Context, preset: RazPreset): File? = runCatching {
        val out = fileFor(context, preset.name)
        out.writeText(RazPresetSerializer.serialize(preset), Charsets.UTF_8)
        Log.i(TAG, "save: ${out.absolutePath} (${out.length()} bytes, ${preset.cards.size} cards)")
        out
    }.onFailure { Log.e(TAG, "save failed", it) }.getOrNull()

    /** Load a preset from disk by file path. */
    fun load(file: File): RazPreset? = runCatching {
        val xml = file.readText(Charsets.UTF_8)
        RazPresetSerializer.deserialize(xml)
    }.onFailure { Log.e(TAG, "load failed: ${file.absolutePath}", it) }.getOrNull()

    /** Delete a preset file. Returns true on success. */
    fun delete(file: File): Boolean = runCatching {
        file.delete()
    }.getOrDefault(false)
}

data class RazPresetEntry(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)
