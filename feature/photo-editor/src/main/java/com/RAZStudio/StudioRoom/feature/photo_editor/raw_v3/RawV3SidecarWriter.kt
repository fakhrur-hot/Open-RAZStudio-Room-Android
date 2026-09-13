/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RAW v3 — production sidecar XMP writer (M12.1b).
 *
 *  Writes a `<sourcename>.xmp` next to the source RAW so re-opening the
 *  same file restores its committed adjustments. Round-trips the same
 *  CRS tag set the preset store uses (Exposure2012, Contrast2012, etc.)
 *  so a v2 user can also feed the file to Lightroom and see the same
 *  global adjustments.
 *
 *  Path resolution mirrors v2's [SidecarStore]:
 *    1. If [sourceUri] is a `file://` URI in a writable directory →
 *       write next to it.
 *    2. If the URI is a SAF tree document whose parent is writable
 *       (we hold a persistable read+write permission) → create a
 *       `.xmp` document in that tree.
 *    3. Otherwise → fall back to `<filesDir>/raw_v3_sidecars/<sha>.xmp`
 *       keyed by the URI's SHA-256.
 *
 *  This is the M12.1b minimum: ONE current sidecar, no revision
 *  history. v2's full revision-history surface lands at M12.3.
 * ─────────────────────────────────────────────────────────────────────────────
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

object RawV3SidecarWriter {

    private const val TAG = "RawV3.SidecarWriter"

    /**
     * Write a sidecar XMP for [sourceUri] carrying [params].
     *
     * Returns the resolved path / URI on success, or null on failure.
     * Failure is non-fatal — the editor still saved the rendered file.
     */
    fun writeNextToSource(
        context: Context,
        sourceUri: Uri,
        params: ShaderParams,
    ): String? {
        val xmpText = RawV3XmpPresetStore.buildPublicCrsXmp(params)

        // Strategy 1: file:// scheme, parent writable.
        if (sourceUri.scheme == "file") {
            val src = File(sourceUri.path ?: return null)
            val parent = src.parentFile
            if (parent != null && parent.canWrite()) {
                val out = File(parent, "${src.nameWithoutExtension}.xmp")
                return runCatching {
                    out.writeText(xmpText)
                    Log.i(TAG, "sidecar written next to source: ${out.absolutePath}")
                    out.absolutePath
                }.onFailure { Log.w(TAG, "file:// sidecar write failed", it) }
                    .getOrNull()
            }
        }

        // Strategy 2: SAF tree document with write permission.
        if (sourceUri.scheme == "content") {
            val sidecarUri = runCatching {
                val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
                    ?: return@runCatching null
                val name = sourceDoc.name ?: return@runCatching null
                val parentDoc = sourceDoc.parentFile ?: return@runCatching null
                if (!parentDoc.canWrite()) return@runCatching null
                val xmpName = "${name.substringBeforeLast('.', name)}.xmp"
                // Delete existing sidecar so the new write replaces it.
                parentDoc.findFile(xmpName)?.delete()
                val newDoc = parentDoc.createFile("application/xml", xmpName)
                    ?: return@runCatching null
                context.contentResolver.openOutputStream(newDoc.uri)?.use { os ->
                    os.write(xmpText.toByteArray(Charsets.UTF_8))
                }
                newDoc.uri
            }.onFailure { Log.w(TAG, "SAF sidecar write failed", it) }.getOrNull()
            if (sidecarUri != null) {
                Log.i(TAG, "sidecar written via SAF: $sidecarUri")
                return sidecarUri.toString()
            }
        }

        // Strategy 3: app-private fallback.
        val sha = sha256(sourceUri.toString())
        val dir = File(context.filesDir, "raw_v3_sidecars").also { it.mkdirs() }
        val out = File(dir, "$sha.xmp")
        return runCatching {
            out.writeText(xmpText)
            Log.i(TAG, "sidecar written to app-private fallback: ${out.absolutePath}")
            out.absolutePath
        }.onFailure { Log.w(TAG, "app-private sidecar write failed", it) }.getOrNull()
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
