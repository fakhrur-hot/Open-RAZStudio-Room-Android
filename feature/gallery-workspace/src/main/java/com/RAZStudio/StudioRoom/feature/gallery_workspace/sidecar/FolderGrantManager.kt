/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of moving fallback sidecars beside their originals (Requirement 4.8). */
data class MigrationReport(
    val migrated: Int,
    val skipped: Int,
    val failed: Int,
) {
    val touchedAnything: Boolean get() = migrated > 0 || failed > 0
}

/**
 * Tracks which directories the app may write into, and turns a photo URI into a
 * writable sidecar document beside it (Requirements 4.1–4.13).
 *
 * ## Why this class has to exist
 *
 * `ACTION_OPEN_DOCUMENT` hands back a **single-document** URI, and there is no
 * supported way to derive its parent. So a sibling `photo.NEF.xmp` simply cannot
 * be created from it — creating a document requires a *parent* document, which
 * only an `ACTION_OPEN_DOCUMENT_TREE` grant provides. Everything here exists to
 * bridge that gap.
 */
@Singleton
class FolderGrantManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Tree URI whose subtree contains [photoUri], or null when no grant covers
     * it (Requirement 4.4).
     *
     * Read live from `persistedUriPermissions` rather than from a local table on
     * purpose: the OS is the authority, and a grant can be revoked at any time
     * without telling the app. A cached copy would confidently report access the
     * app no longer has.
     */
    fun grantFor(photoUri: Uri): Uri? {
        val photoDocId = runCatching { DocumentsContract.getDocumentId(photoUri) }.getOrNull()
            ?: return null
        return context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isWritePermission }
            .map { it.uri }
            .filter { it.authority == photoUri.authority }
            .firstOrNull { treeUri -> isDescendant(treeUri, photoDocId) }
    }

    /**
     * True when [photoDocId] names a document inside [treeUri].
     *
     * Compared as document-ID path prefixes — for ExternalStorageProvider a tree
     * is `primary:DCIM` and a document inside it `primary:DCIM/x/y.NEF`. The
     * trailing separator matters: without it `primary:DCIM` would also "contain"
     * `primary:DCIMBACKUP`.
     */
    private fun isDescendant(treeUri: Uri, photoDocId: String): Boolean {
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return false
        return photoDocId == treeDocId || photoDocId.startsWith("$treeDocId/")
    }

    /** The intent for asking the user to grant a folder (Requirement 4.4). */
    fun buildTreeRequestIntent(initialUri: Uri? = null): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            if (initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }

    /** Persist a tree grant returned by [buildTreeRequestIntent]. */
    fun persistTreeGrant(treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        Log.i(TAG, "persisted tree grant: $treeUri")
        true
    }.getOrElse {
        Log.w(TAG, "failed to persist tree grant $treeUri: ${it.message}")
        false
    }

    /**
     * The sidecar document beside [photoUri], creating it if absent. Null when no
     * grant covers the photo's directory.
     *
     * Named `<original filename>.xmp` (Requirement 4.5), so it sits next to the
     * photo and is picked up by other tools that look for a sidecar there.
     */
    fun resolveOrCreateSidecarDoc(photoUri: Uri, displayName: String): Uri? {
        val treeUri = grantFor(photoUri) ?: return null
        val parent = parentDocumentOf(photoUri, treeUri) ?: return null
        val sidecarName = "$displayName.xmp"

        parent.findFile(sidecarName)?.let { return it.uri }

        // MIME choice matters. A DocumentsProvider appends an extension when the
        // requested mime maps to a known one and the name lacks it, so asking for
        // "text/xml" can yield "IMG.NEF.xmp.xml". octet-stream has no canonical
        // extension, so the name is kept verbatim.
        val created = runCatching {
            parent.createFile(MIME_OPAQUE, sidecarName)
        }.getOrNull()

        if (created == null) {
            Log.w(TAG, "could not create sidecar $sidecarName under $treeUri")
            return null
        }
        if (created.name != sidecarName) {
            Log.w(TAG, "provider renamed sidecar to '${created.name}' (wanted '$sidecarName')")
        }
        return created.uri
    }

    /**
     * The photo's containing directory as a [DocumentFile] under [treeUri].
     *
     * Walks down from the tree root by document-ID path segments. A tree
     * DocumentFile cannot be asked for "the parent of this arbitrary document",
     * so the containing folder has to be reached by traversal.
     */
    private fun parentDocumentOf(photoUri: Uri, treeUri: Uri): DocumentFile? {
        val treeRoot = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        val photoDocId = runCatching { DocumentsContract.getDocumentId(photoUri) }
            .getOrNull() ?: return null

        if (photoDocId == treeDocId) return treeRoot

        val relative = photoDocId.removePrefix("$treeDocId/")
        if (relative == photoDocId) return null   // not actually a descendant

        // Drop the filename; everything before it is the directory chain.
        val segments = relative.split('/').dropLast(1)
        var cursor: DocumentFile = treeRoot
        for (segment in segments) {
            cursor = cursor.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return cursor
    }

    /**
     * Re-check every grant in use and report the tree URIs that have gone away
     * (Requirement 4.4 / 4.6).
     *
     * **Android provides no revocation callback.** A user can withdraw access in
     * system settings and the app finds out only when a write fails. Polling on
     * resume is the only mechanism available, and doing it here means affected
     * photos fall back to app storage BEFORE the user makes an edit that would
     * otherwise be lost at write time.
     */
    fun revalidate(previouslyHeld: Set<Uri>): List<Uri> {
        val live = context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { it.uri }
            .toSet()
        val lost = previouslyHeld.filterNot { it in live }
        if (lost.isNotEmpty()) Log.w(TAG, "grants revoked while away: $lost")
        return lost
    }

    /** All write-capable tree grants currently held. */
    fun heldTreeGrants(): Set<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { it.uri }
            .toSet()

    private companion object {
        const val TAG = "FolderGrantManager"
        const val MIME_OPAQUE = "application/octet-stream"
    }
}
