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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the disclosure needs, computed before anything is written
 * (Requirements 16.8–16.11a, 16.13).
 */
data class ImportPlan(
    val projectId: Long,
    /** Persistable grant obtained — link in place, no copy (Req 16.14). */
    val linked: List<Uri>,
    /** No persistable grant — must be copied to survive a cold start (Req 16.10). */
    val toCopy: List<Uri>,
    /** Already present in the target project; skipped and revealed instead (Req 16.7). */
    val duplicates: List<Uri>,
    /** Photo ids the duplicates resolved to, so the reveal has a target. */
    val duplicateExistingIds: List<Long>,
    val copyBytes: Long,
    val freeBytesNow: Long,
) {
    val freeBytesAfter: Long get() = freeBytesNow - copyBytes
    val insufficientStorage: Boolean get() = freeBytesAfter < SAFETY_MARGIN_BYTES
    val shortfallBytes: Long get() = (copyBytes + SAFETY_MARGIN_BYTES) - freeBytesNow
    val needsDisclosure: Boolean get() = toCopy.isNotEmpty()
    val nothingToDo: Boolean get() = linked.isEmpty() && toCopy.isEmpty()

    private companion object {
        /** Never fill the last 64 MB; a full data partition breaks far more than this. */
        const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}

/** Result of executing a plan. */
sealed interface SharedImportOutcome {
    data class Added(val photoIds: List<Long>, val duplicates: Int) : SharedImportOutcome
    /** Nothing inserted; every URI was already in the project. */
    data class AllDuplicates(val existingPhotoIds: List<Long>) : SharedImportOutcome
    data class Failed(val reason: String) : SharedImportOutcome
}

/**
 * Adds share-received photos to a project (Requirement 16).
 *
 * ## Why this cannot just link the URI
 *
 * A URI arriving in `EXTRA_STREAM` from `ACTION_SEND` is granted **for the life
 * of that intent**. Unless the *sending* app set
 * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` — most do not —
 * `takePersistableUriPermission` throws. A row built on such a URI reads fine
 * now and is dead after the next cold start: the user adds a photo, works with
 * it, and later finds it unreachable with no explanation.
 *
 * So when no grant can be taken the bytes are **copied** into the project. That
 * is disclosed with its size first, and declining cancels the add rather than
 * inserting a row that is guaranteed to break.
 *
 * ## Why plan/execute are separate
 *
 * `plan()` computes everything the disclosure must state without writing
 * anything. `execute()` does the writes. No UI dependency in either, so the
 * grant attempt, the space arithmetic, the copy and the rollback are all
 * testable without standing up a screen.
 */
@Singleton
class SharedImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
) {

    /** Classify [uris] without writing anything. */
    suspend fun plan(projectId: Long, uris: List<Uri>): ImportPlan {
        val linked = mutableListOf<Uri>()
        val toCopy = mutableListOf<Uri>()
        val duplicates = mutableListOf<Uri>()
        val duplicateIds = mutableListOf<Long>()
        var copyBytes = 0L

        for (uri in uris) {
            val meta = PhotoMetadataProbe.probe(context.contentResolver, uri)

            val existing = photoDao.getBySourceUri(projectId, uri.toString())
                ?: findByFingerprint(projectId, uri, meta.sizeBytes)
            if (existing != null) {
                duplicates += uri
                duplicateIds += existing.id
                continue
            }

            if (tryTakePersistableGrant(uri)) {
                linked += uri
            } else {
                toCopy += uri
                copyBytes += meta.sizeBytes
            }
        }

        return ImportPlan(
            projectId = projectId,
            linked = linked,
            toCopy = toCopy,
            duplicates = duplicates,
            duplicateExistingIds = duplicateIds,
            copyBytes = copyBytes,
            freeBytesNow = freeBytes(),
        )
    }

    /**
     * Execute [plan]. Rolls back completely on any failure.
     *
     * **Ordering is what makes "no partial rows" true** (Requirement 16.13b):
     * the copy finishes and its byte length is verified against the source
     * BEFORE any row is inserted. A failure part-way therefore has no row to
     * clean up — only the partial file, which is deleted on the way out.
     */
    suspend fun execute(plan: ImportPlan): SharedImportOutcome {
        // Failure reasons are user-visible (rendered through the localized
        // gallery_copy_failed frame), so they come from resources (Req 13.1)
        // and the byte count is a locale-aware size string (Req 13.2).
        val res = context.resources
        if (plan.insufficientStorage) {
            return SharedImportOutcome.Failed(
                res.getString(
                    com.RAZStudio.StudioRoom.core.resources.R.string.gallery_import_fail_no_space,
                    android.text.format.Formatter.formatFileSize(context, plan.shortfallBytes),
                )
            )
        }
        if (plan.nothingToDo) {
            return SharedImportOutcome.AllDuplicates(plan.duplicateExistingIds)
        }

        val inserted = mutableListOf<Long>()

        for (uri in plan.linked) {
            val id = insertRow(plan.projectId, uri, copiedFile = null)
                ?: return rollback(
                    plan.projectId, inserted,
                    res.getString(com.RAZStudio.StudioRoom.core.resources.R.string
                        .gallery_import_fail_index_linked),
                )
            inserted += id
        }

        for (uri in plan.toCopy) {
            val meta = PhotoMetadataProbe.probe(context.contentResolver, uri)
            val copied = copyIntoProject(plan.projectId, uri, meta)
                ?: return rollback(
                    plan.projectId, inserted,
                    res.getString(com.RAZStudio.StudioRoom.core.resources.R.string
                        .gallery_import_fail_copy, meta.displayName),
                )
            val id = insertRow(plan.projectId, Uri.fromFile(copied), copiedFile = copied, meta = meta)
            if (id == null) {
                copied.delete()
                return rollback(
                    plan.projectId, inserted,
                    res.getString(com.RAZStudio.StudioRoom.core.resources.R.string
                        .gallery_import_fail_index, meta.displayName),
                )
            }
            inserted += id
        }

        Log.i(TAG, "shared import: added=${inserted.size} duplicates=${plan.duplicates.size}")
        return SharedImportOutcome.Added(inserted, plan.duplicates.size)
    }

    /**
     * Delete files in a project's `originals/` that no Photo_Entry references
     * (Requirement 16.13d).
     *
     * Closes the one window `execute` cannot: the process being killed between
     * the copy completing and the insert committing. Cheaper and less fragile
     * than a write-ahead journal for the same guarantee.
     */
    suspend fun reapOrphanedCopies(projectIds: List<Long>) {
        for (projectId in projectIds) {
            val dir = originalsDir(projectId)
            if (!dir.isDirectory) continue
            val referenced = photoDao.getAllIds(projectId)
                .mapNotNull { photoDao.getById(it) }
                .filter { it.sourceIsCopy }
                .mapNotNull { Uri.parse(it.sourceUri).path }
                .toSet()
            dir.listFiles()?.forEach { file ->
                if (file.absolutePath !in referenced) {
                    val deleted = file.delete()
                    Log.i(TAG, "reaped orphaned copy ${file.name} (deleted=$deleted)")
                }
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Duplicate detection for a photo that would be COPIED.
     *
     * `getBySourceUri` cannot see these: a Copied_Original is stored under a
     * fresh timestamped `file://` path, so the incoming share URI never appears
     * in the table. Re-sharing the same photo therefore produced a second full
     * copy every time — silently burning the file's size in storage on each add
     * (measured: two 21 MB copies of one NEF).
     *
     * The Content_Fingerprint is what makes this detectable, since it is derived
     * from the bytes rather than from the location.
     */
    private suspend fun findByFingerprint(
        projectId: Long,
        uri: Uri,
        sizeBytes: Long,
    ): PhotoEntity? {
        val fp = ContentFingerprint.compute(context.contentResolver, uri, sizeBytes)
            ?: return null
        return photoDao.getByFingerprint(projectId, fp).firstOrNull()
    }

    private fun tryTakePersistableGrant(uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, rw)
            return true
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            return true
        }.onFailure {
            // The EXPECTED case for a share URI (Requirement 16.9), not an error.
            Log.i(TAG, "no persistable grant for shared uri (will copy): ${it.message}")
        }
        return false
    }

    /**
     * Copy the bytes in, verifying length before returning. Returns null and
     * removes the partial file on any failure.
     */
    private fun copyIntoProject(
        projectId: Long,
        uri: Uri,
        meta: PhotoMetadata,
    ): File? {
        val dir = originalsDir(projectId).apply { mkdirs() }
        val ext = meta.displayName.substringAfterLast('.', "").ifBlank { "img" }
        // Named by a timestamp because the photoId is not known until the row is
        // inserted, and the row must not exist before the copy succeeds.
        val target = File(dir, "shared_${System.currentTimeMillis()}.$ext")

        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("could not open shared photo")

            if (meta.sizeBytes > 0 && target.length() != meta.sizeBytes) {
                error("short copy: ${target.length()} of ${meta.sizeBytes} bytes")
            }
            true
        }.getOrElse {
            Log.w(TAG, "copyIntoProject failed for ${meta.displayName}: ${it.message}")
            false
        }

        if (!ok) {
            target.delete()
            return null
        }
        return target
    }

    private suspend fun insertRow(
        projectId: Long,
        sourceUri: Uri,
        copiedFile: File?,
        meta: PhotoMetadata? = null,
    ): Long? {
        val m = meta ?: PhotoMetadataProbe.probe(context.contentResolver, sourceUri)
        val fingerprint = ContentFingerprint.compute(
            context.contentResolver, sourceUri, m.sizeBytes,
        )
        return runCatching {
            photoDao.insert(
                PhotoEntity(
                    projectId = projectId,
                    sourceUri = sourceUri.toString(),
                    displayName = m.displayName,
                    mimeType = m.mimeType,
                    sourceFormat = m.format.ordinal,
                    sizeBytes = m.sizeBytes,
                    widthPx = m.widthPx,
                    heightPx = m.heightPx,
                    capturedAt = m.capturedAt,
                    addedAt = System.currentTimeMillis(),
                    // A copy lives in app-private storage, so it is always
                    // reachable — no grant involved (Requirement 16.15).
                    uriPermissionOk = true,
                    sourceIsCopy = copiedFile != null,
                    contentFingerprint = fingerprint,
                        stackKey = com.RAZStudio.StudioRoom.core.database.model
                            .StackKeys.of(m.displayName),
                    cameraMake = m.cameraMake,
                    cameraModel = m.cameraModel,
                    lensModel = m.lensModel,
                    iso = m.iso,
                    apertureF = m.apertureF,
                    shutterSpeed = m.shutterSpeed,
                    focalLengthMm = m.focalLengthMm,
                    gpsLat = m.gpsLat,
                    gpsLon = m.gpsLon,
                    rating = 0,
                    flagState = 0,
                    colorLabel = 0,
                )
            )
        }.getOrNull()
    }

    /** Undo everything this run inserted, so a failure leaves no trace. */
    private suspend fun rollback(
        projectId: Long,
        insertedIds: List<Long>,
        reason: String,
    ): SharedImportOutcome.Failed {
        Log.w(TAG, "shared import rolling back ${insertedIds.size} row(s): $reason")
        for (id in insertedIds) {
            val photo = photoDao.getById(id) ?: continue
            if (photo.sourceIsCopy) {
                runCatching { File(Uri.parse(photo.sourceUri).path.orEmpty()).delete() }
            }
            runCatching { photoDao.delete(photo) }
        }
        return SharedImportOutcome.Failed(reason)
    }

    private fun originalsDir(projectId: Long): File =
        File(context.filesDir, "gallery/projects/$projectId/originals")

    private fun freeBytes(): Long = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private companion object {
        const val TAG = "SharedImportManager"
    }
}
