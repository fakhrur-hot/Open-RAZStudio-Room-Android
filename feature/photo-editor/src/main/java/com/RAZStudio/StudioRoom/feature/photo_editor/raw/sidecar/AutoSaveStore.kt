/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  Auto-save crash/background recovery store.
 *
 *  Persists the in-flight editor state (source URI + serialized RawAction
 *  list) to app-private storage every couple of seconds while edits are
 *  happening. On a cold start of the editor (process death, swipe-from-
 *  recents, OS kill), the editor calls [loadLast] to recover.
 *
 *  Storage path: context.filesDir / "raw_autosave" / "current.xml"
 *  Pointer file: context.filesDir / "raw_autosave" / "current.uri"
 *
 *  XML payload format matches [RawActionSerializer] so the existing
 *  round-trip code paths apply unchanged.
 *
 *  Not the same store as [SidecarStore] — that writes XMP next to the
 *  source RAW (slow on SAF, requires user permission). This one writes
 *  to fast app-private storage and is invisible to the user until they
 *  return to the editor.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar

import android.content.Context
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawActionSerializer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import java.io.File

object AutoSaveStore {

    private const val TAG = "RawAutoSave"
    private const val DIR_NAME    = "raw_autosave"
    private const val ACTIONS_NAME = "current.xml"
    private const val URI_NAME    = "current.uri"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /**
     * Write the current source URI + actions list to disk. Atomic via
     * write-then-rename so a process kill during write can't leave a
     * truncated file. Quiet — failures are logged but never thrown so
     * the auto-save timer can't crash the editor.
     */
    fun save(context: Context, sourceUri: Uri?, actions: List<RawAction>) {
        runCatching {
            val d = dir(context)
            // Actions XML
            val xml = RawActionSerializer.serialize(actions)
            val tmpA = File(d, "$ACTIONS_NAME.tmp")
            tmpA.writeText(xml, Charsets.UTF_8)
            val outA = File(d, ACTIONS_NAME)
            if (outA.exists()) outA.delete()
            if (!tmpA.renameTo(outA)) {
                // Some filesystems can't atomically rename across — fall back to copy.
                outA.writeText(xml, Charsets.UTF_8)
                tmpA.delete()
            }
            // URI pointer (separate file so we can detect "no last edit"
            // by URI absence even if XML is partial).
            val tmpU = File(d, "$URI_NAME.tmp")
            tmpU.writeText(sourceUri?.toString() ?: "", Charsets.UTF_8)
            val outU = File(d, URI_NAME)
            if (outU.exists()) outU.delete()
            if (!tmpU.renameTo(outU)) {
                outU.writeText(sourceUri?.toString() ?: "", Charsets.UTF_8)
                tmpU.delete()
            }
        }.onFailure {
            Log.w(TAG, "save failed (continuing): ${it.message}")
        }
    }

    /**
     * Read whatever was last written. Returns null when no auto-save
     * exists yet (first run / explicit clear). Returns the URI + parsed
     * actions list otherwise. Caller decides whether to silently restore
     * or prompt the user.
     */
    fun loadLast(context: Context): Restored? {
        return runCatching {
            val d = dir(context)
            val u = File(d, URI_NAME)
            val a = File(d, ACTIONS_NAME)
            if (!u.exists() || !a.exists()) return@runCatching null
            val uriStr = u.readText(Charsets.UTF_8).trim()
            if (uriStr.isEmpty()) return@runCatching null
            val xml = a.readText(Charsets.UTF_8)
            val actions = RawActionSerializer.deserialize(xml)
            if (actions.isEmpty()) {
                // Empty actions means "Original" wasn't even seeded — treat as
                // no real edit. Skip.
                return@runCatching null
            }
            Restored(Uri.parse(uriStr), actions)
        }.onFailure {
            Log.w(TAG, "loadLast failed: ${it.message}")
        }.getOrNull()
    }

    /** Forget the last auto-save (call after the user successfully exports / leaves the editor). */
    fun clear(context: Context) {
        runCatching {
            val d = dir(context)
            File(d, URI_NAME).delete()
            File(d, ACTIONS_NAME).delete()
        }
    }

    data class Restored(val sourceUri: Uri, val actions: List<RawAction>)
}
