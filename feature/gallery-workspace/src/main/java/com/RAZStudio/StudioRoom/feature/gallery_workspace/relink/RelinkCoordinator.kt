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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.relink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.ContentFingerprint
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoMetadataProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Match strength for a directory-relink candidate, so the UI can say "strong
 * match" rather than just "matched" (Requirement 10.7a).
 *
 * A filename match and a fingerprint match are not the same claim, and
 * presenting both as "matched" invites the user to accept a wrong relink.
 * [NameAndSize] sits between them because byte-size agreement rules out most
 * accidental name collisions without the fingerprint being available.
 */
enum class MatchConfidence(val score: Float) {
    /** Size + both 64 KB windows agree. Survives a rename (Req 10.7b). */
    Fingerprint(1.0f),

    /** Filename and byte size agree, fingerprint absent or inconclusive. */
    NameAndSize(0.75f),

    /** Filename alone. */
    NameOnly(0.5f),
}

/** One directory-relink candidate: which photo, which document, how sure. */
data class RelinkCandidate(
    val photoId: Long,
    val uri: Uri,
    val displayName: String,
    val confidence: MatchConfidence,
)

/**
 * Outcome of a directory relink (Requirement 10.7d).
 *
 * [applied] — [Fingerprint] matches, already written to the database; no
 * confirmation needed because the identity claim is strong enough to act on.
 * [pendingConfirmation] — [NameAndSize]/[NameOnly] matches the UI must show
 * the user before writing, since a name match alone is not proof of identity.
 * [ambiguous] — a photo with two or more equally-scored candidates; the
 * coordinator never guesses between them. [unmatched] — no candidate found.
 */
data class RelinkReport(
    val applied: List<RelinkCandidate>,
    val pendingConfirmation: List<RelinkCandidate>,
    val ambiguous: List<Long>,
    val unmatched: List<Long>,
)

/**
 * Verifies Photo_Entry readability and repoints lapsed source URIs
 * (Requirement 10).
 *
 * Reuses the same metadata probe and Content_Fingerprint used at import
 * ([PhotoMetadataProbe], [ContentFingerprint]) so a relinked photo is indexed
 * identically to a freshly imported one. Only `sourceUri` and the metadata
 * that follows FROM the file (name, size, dimensions, capture time,
 * fingerprint, reachability) change on a relink — rating, flag, colour label,
 * and `sourceIsCopy` are preserved because they describe the Photo_Entry, not
 * the file it currently points at.
 */
@Singleton
class RelinkCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
) {

    /**
     * Verify every photo in [projectId] without decoding image data
     * (Requirements 10.1, 10.2), updating `uriPermissionOk` wherever it
     * changed. Returns the ids that are unreachable after the check.
     */
    suspend fun verify(projectId: Long): List<Long> = withContext(Dispatchers.IO) {
        val unreachable = mutableListOf<Long>()
        for (photo in photoDao.getAllForProject(projectId)) {
            val readable = isReadable(Uri.parse(photo.sourceUri))
            if (readable != photo.uriPermissionOk) {
                photoDao.update(photo.copy(uriPermissionOk = readable))
            }
            if (!readable) unreachable += photo.id
        }
        unreachable
    }

    /**
     * Repoint one photo to [newUri] (Requirement 10.6), preserving its edits,
     * rating, flag, and colour label. Returns false when [newUri] cannot be
     * read at all — the row is left untouched rather than relinked to a dead
     * end.
     */
    suspend fun relinkOne(photoId: Long, newUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val photo = photoDao.getById(photoId) ?: return@withContext false
        applyRelink(photo, newUri)
    }

    /**
     * Match every unreachable photo in [projectId] against the contents of
     * [treeUri] and repoint every [Fingerprint][MatchConfidence.Fingerprint]
     * match in one action (Requirement 10.7).
     *
     * Fingerprint matching scans every candidate regardless of name — that is
     * the only way to recover a file that was renamed (10.7b). A candidate
     * already claimed by an earlier photo in this scan (applied OR queued for
     * confirmation) is removed from later consideration, so two Photo_Entries
     * can never both point at the same file.
     */
    suspend fun relinkByDirectory(projectId: Long, treeUri: Uri): RelinkReport =
        withContext(Dispatchers.IO) {
            val unreachable = photoDao.getUnreachable(projectId)
            if (unreachable.isEmpty()) {
                return@withContext RelinkReport(emptyList(), emptyList(), emptyList(), emptyList())
            }

            val treeRoot = DocumentFile.fromTreeUri(context, treeUri)
            val allCandidates = treeRoot?.listFiles()?.filter { it.isFile }.orEmpty()
            val claimed = mutableSetOf<Uri>()

            val applied = mutableListOf<RelinkCandidate>()
            val pending = mutableListOf<RelinkCandidate>()
            val ambiguous = mutableListOf<Long>()
            val unmatched = mutableListOf<Long>()

            // Computed lazily and cached: only candidates a fingerprint-bearing
            // photo actually needs to compare against are ever hashed.
            val fingerprintCache = mutableMapOf<Uri, String?>()
            fun fingerprintOf(doc: DocumentFile): String? =
                fingerprintCache.getOrPut(doc.uri) {
                    val size = doc.length()
                    if (size <= 0L) null else ContentFingerprint.compute(
                        context.contentResolver, doc.uri, size,
                    )
                }

            for (photo in unreachable) {
                val available = allCandidates.filter { it.uri !in claimed }

                val fingerprintMatches = photo.contentFingerprint?.let { fp ->
                    available.filter { fingerprintOf(it) == fp }
                }.orEmpty()

                if (fingerprintMatches.isNotEmpty()) {
                    if (fingerprintMatches.size > 1) {
                        ambiguous += photo.id
                    } else {
                        val doc = fingerprintMatches.single()
                        if (applyRelink(photo, doc.uri)) {
                            applied += RelinkCandidate(
                                photo.id, doc.uri, doc.name.orEmpty(), MatchConfidence.Fingerprint,
                            )
                            claimed += doc.uri
                        } else {
                            unmatched += photo.id
                        }
                    }
                    continue
                }

                val nameMatches = available.filter { it.name == photo.displayName }
                val nameAndSizeMatches = nameMatches.filter { it.length() == photo.sizeBytes }

                when {
                    nameAndSizeMatches.size == 1 -> {
                        val doc = nameAndSizeMatches.single()
                        pending += RelinkCandidate(
                            photo.id, doc.uri, doc.name.orEmpty(), MatchConfidence.NameAndSize,
                        )
                        claimed += doc.uri
                    }
                    nameAndSizeMatches.size > 1 -> ambiguous += photo.id
                    nameMatches.size == 1 -> {
                        val doc = nameMatches.single()
                        pending += RelinkCandidate(
                            photo.id, doc.uri, doc.name.orEmpty(), MatchConfidence.NameOnly,
                        )
                        claimed += doc.uri
                    }
                    // Req 10.7c — filename shared by 2+ candidates and the
                    // fingerprint (tried above) could not disambiguate them.
                    nameMatches.size > 1 -> ambiguous += photo.id
                    else -> unmatched += photo.id
                }
            }

            RelinkReport(applied, pending, ambiguous, unmatched)
        }

    /**
     * Write the [pending] candidates a user has confirmed (Requirement 10.7).
     * Returns how many were actually applied — a candidate can still fail if
     * the document became unreadable between the scan and the confirmation.
     */
    suspend fun confirmPending(pending: List<RelinkCandidate>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (candidate in pending) {
            val photo = photoDao.getById(candidate.photoId) ?: continue
            if (applyRelink(photo, candidate.uri)) count++
        }
        count
    }

    /**
     * The actual repoint: re-probe [newUri] exactly as import does, and write
     * back only the fields that follow from the file plus reachability.
     * Everything else on [photo] — rating, flag, colour label, `sourceIsCopy`
     * — is carried through via `copy()`.
     */
    private suspend fun applyRelink(photo: PhotoEntity, newUri: Uri): Boolean {
        if (!isReadable(newUri)) return false
        takePersistableGrant(newUri)

        val meta = PhotoMetadataProbe.probe(context.contentResolver, newUri)
        val fingerprint = ContentFingerprint.compute(
            resolver = context.contentResolver,
            uri = newUri,
            sizeBytes = meta.sizeBytes,
        )

        return runCatching {
            photoDao.update(
                photo.copy(
                    sourceUri = newUri.toString(),
                    displayName = meta.displayName,
                    mimeType = meta.mimeType,
                    sourceFormat = meta.format.ordinal,
                    sizeBytes = meta.sizeBytes,
                    widthPx = meta.widthPx,
                    heightPx = meta.heightPx,
                    capturedAt = meta.capturedAt,
                    uriPermissionOk = true,
                    contentFingerprint = fingerprint,
                )
            )
            true
        }.getOrElse {
            Log.w(TAG, "relink write failed for photo ${photo.id}: ${it.message}")
            false
        }
    }

    /** Verification opens and immediately closes an input stream — no decode. */
    private fun isReadable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { } != null
    }.getOrDefault(false)

    /**
     * Best-effort persistable grant on the relinked document, mirroring
     * [com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoImportRepository].
     * Read-only is accepted when write is refused; failure here does not fail
     * the relink — `uriPermissionOk` is what [verify] actually checks on the
     * next open, not whether this call succeeded.
     */
    private fun takePersistableGrant(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, readWrite)
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.i(TAG, "no persistable grant for $uri: ${it.message}") }
    }

    private companion object {
        const val TAG = "RelinkCoordinator"
    }
}
