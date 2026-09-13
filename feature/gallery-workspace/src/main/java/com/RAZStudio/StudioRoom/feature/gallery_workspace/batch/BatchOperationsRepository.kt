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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.batch

import android.content.Context
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import com.RAZStudio.StudioRoom.feature.gallery_workspace.thumbnail.ThumbnailWorker
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Cache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a batch move/copy, for the "N moved, M skipped" report. */
data class BatchTransferResult(
    val transferred: Int,
    /** Skipped because the destination already holds the same source URI (Req 3.5). */
    val skippedDuplicates: Int,
    val failed: Int,
)

/**
 * Batch operations over selected photos (Requirement 8).
 *
 * Attribute batches (flag/rating/label) are single UPDATE statements. Move and
 * copy relocate the app-owned files a Photo_Entry drags along — Copied_Original
 * files, fallback sidecars, cached thumbnails — because every one of them lives
 * under `filesDir/gallery/projects/<id>/…` and PROJECT DELETION REMOVES THAT
 * WHOLE DIRECTORY: a moved row still pointing into its old project's directory
 * would lose its files the day the old project is deleted.
 *
 * File order is copy → DB update → delete source, so a crash mid-operation can
 * only leave harmless duplicate files (the originals orphan-reaper and the
 * thumbnail cap both tolerate strays), never a row pointing at nothing.
 */
@Singleton
class BatchOperationsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
    private val editDao: EditDao,
    private val thumbnailDao: ThumbnailDao,
    private val thumbnails: ThumbnailWorker,
) {

    // ── Attributes (Req 8.2) ─────────────────────────────────────────────────
    // Batches APPLY a state (Lightroom semantics) — no per-photo toggling, so
    // a mixed selection converges instead of flipping each photo differently.

    suspend fun setFlagState(ids: Collection<Long>, state: Int) =
        photoDao.setFlagStateBatch(ids.toList(), state)

    suspend fun setRating(ids: Collection<Long>, stars: Int) =
        photoDao.setRatingBatch(ids.toList(), stars.coerceIn(0, 5))

    suspend fun setColorLabel(ids: Collection<Long>, label: Int) =
        photoDao.setColorLabelBatch(ids.toList(), label.coerceIn(0, 15))

    // ── Move (Req 8.3, 8.4, 8.5a, 8.5c) ─────────────────────────────────────

    /**
     * Move [ids] to [destProjectId]. The row keeps its identity (an UPDATE of
     * projectId), so the photoId-keyed edit and thumbnail rows carry over for
     * free — "edits travel with the photo" (Req 8.4) is structural, not copied.
     */
    suspend fun moveToProject(ids: Collection<Long>, destProjectId: Long): BatchTransferResult {
        var moved = 0
        var skipped = 0
        var failed = 0
        for (photoId in ids) {
            val photo = photoDao.getById(photoId) ?: run { failed++; null } ?: continue
            if (photo.projectId == destProjectId) { skipped++; continue }
            // The (projectId, sourceUri) unique index would reject the reparent;
            // check first so the photo cleanly stays in the source project.
            if (photoDao.getBySourceUri(destProjectId, photo.sourceUri) != null) {
                skipped++
                continue
            }
            runCatching {
                // 1) Copy phase — nothing in the DB or the source dir changes yet.
                val newUri = stageCopiedOriginal(photo, destProjectId) ?: photo.sourceUri
                val edit = editDao.getByPhotoId(photoId)
                val newSidecar = stageFallbackSidecar(edit, photo.projectId, destProjectId, photoId)
                val thumb = thumbnailDao.getByPhotoId(photoId)
                val newThumb = stageThumbFile(thumb, destProjectId, photoId)

                // 2) DB phase.
                photoDao.reparent(photoId, destProjectId, newUri)
                if (edit != null && newSidecar != null) {
                    editDao.update(edit.copy(sidecarPath = newSidecar))
                }
                if (thumb != null && newThumb != null) {
                    thumbnailDao.update(thumb.copy(path = newThumb))
                }

                // 3) Delete-source phase (Req 8.5c) — only after the copies and
                //    the rows agree on the new locations.
                if (newUri != photo.sourceUri) {
                    deleteQuietly(Uri.parse(photo.sourceUri).path)
                }
                if (edit != null && newSidecar != null && newSidecar != edit.sidecarPath) {
                    deleteQuietly(edit.sidecarPath)
                }
                if (thumb != null && newThumb != null && newThumb != thumb.path) {
                    deleteQuietly(thumb.path)
                }

                // Req 8.5b — no cached thumbnail to carry: enqueue at prefetch
                // priority in the destination instead of blocking here.
                if (thumb == null) thumbnails.prefetch(destProjectId, listOf(photoId))
            }.onSuccess { moved++ }.onFailure {
                Log.w(TAG, "move of $photoId failed: ${it.message}")
                failed++
            }
        }
        Log.i(TAG, "move → $destProjectId: moved=$moved skipped=$skipped failed=$failed")
        return BatchTransferResult(moved, skipped, failed)
    }

    // ── Copy (Req 8.3, 8.5) ──────────────────────────────────────────────────

    /**
     * Copy [ids] into [destProjectId] as INDEPENDENT entries (Req 8.5): a new
     * photo row, its own edit record, its own fallback-sidecar file, its own
     * thumbnail file. A beside-original sidecar is intentionally shared — that
     * location contract means "the edits live with the photo file", and both
     * entries reference the same photo file.
     */
    suspend fun copyToProject(ids: Collection<Long>, destProjectId: Long): BatchTransferResult {
        var copied = 0
        var skipped = 0
        var failed = 0
        for (photoId in ids) {
            val photo = photoDao.getById(photoId) ?: run { failed++; null } ?: continue
            if (photo.projectId == destProjectId) { skipped++; continue }
            if (photoDao.getBySourceUri(destProjectId, photo.sourceUri) != null) {
                skipped++
                continue
            }
            runCatching {
                // A Copied_Original must be duplicated: two rows sharing one
                // app-owned file would break removal, which deletes the file
                // with the entry (Req 16.17).
                val newUri = stageCopiedOriginal(photo, destProjectId) ?: photo.sourceUri

                val newId = photoDao.insert(
                    photo.copy(
                        id = 0,
                        projectId = destProjectId,
                        sourceUri = newUri,
                        // A copy is a new entry in that project.
                        addedAt = System.currentTimeMillis(),
                    )
                )

                // Independent edit record (Req 8.5). Fallback sidecars get their
                // own file under the destination project keyed by the NEW id.
                editDao.getByPhotoId(photoId)?.let { edit ->
                    val destPath = if (edit.sidecarBesideOriginal) {
                        edit.sidecarPath
                    } else {
                        copySidecarFile(edit.sidecarPath, destProjectId, newId)
                            ?: return@let   // unreadable sidecar → copy has no edit record
                    }
                    editDao.insert(
                        EditEntity(
                            photoId = newId,
                            sidecarPath = destPath,
                            sidecarBesideOriginal = edit.sidecarBesideOriginal,
                            revisionCount = edit.revisionCount,
                            updatedAt = edit.updatedAt,
                        )
                    )
                }

                // Thumbnail by FILE COPY, never regeneration (Req 8.5a).
                val thumb = thumbnailDao.getByPhotoId(photoId)
                val newThumbPath = stageThumbFile(thumb, destProjectId, newId)
                if (thumb != null && newThumbPath != null) {
                    thumbnailDao.insert(
                        ThumbnailEntity(
                            photoId = newId,
                            path = newThumbPath,
                            bytes = thumb.bytes,
                            generatedAt = thumb.generatedAt,
                            lastAccessedAt = System.currentTimeMillis(),
                        )
                    )
                } else {
                    thumbnails.prefetch(destProjectId, listOf(newId))   // Req 8.5b
                }
            }.onSuccess { copied++ }.onFailure {
                Log.w(TAG, "copy of $photoId failed: ${it.message}")
                failed++
            }
        }
        Log.i(TAG, "copy → $destProjectId: copied=$copied skipped=$skipped failed=$failed")
        return BatchTransferResult(copied, skipped, failed)
    }

    // ── Remove (Req 8.6, subject to 3.9; undo per Req 14.32) ─────────────────

    /**
     * Everything needed to undo a removal (rows) and to finish it (files).
     * "Undo for the rows" (Req 14.32) is made real by deferring FILE deletion:
     * rows are deleted immediately (grid updates), the app-owned files —
     * thumbnails, fallback sidecars, Copied_Originals — are deleted only when
     * the undo window closes via [commitRemove]. If the process dies in the
     * window, the stranded files are orphans the reaper and the thumbnail cap
     * already tolerate — never a row pointing at nothing.
     */
    data class RemovalTicket(
        val photos: List<PhotoEntity>,
        val edits: List<EditEntity>,
        val thumbs: List<ThumbnailEntity>,
    ) {
        val count: Int get() = photos.size
    }

    /** Delete the ROWS for [ids]; files stay until [commitRemove]. */
    // ── Trash (schema v4) ────────────────────────────────────────────────────

    /**
     * Move [ids] to the trash: the rows stay, flagged with `deletedAt`, and every
     * grid query hides them. Replaces the old immediate delete-with-snackbar,
     * where a mis-tap became permanent the moment the snackbar expired.
     * Thumbnails and sidecars are deliberately left alone so a restore is free.
     */
    suspend fun moveToTrash(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        runCatching { photoDao.softDelete(ids.toList(), now) }
            .onFailure { Log.w(TAG, "moveToTrash failed: ${it.message}") }
        Log.i(TAG, "moveToTrash: ${ids.size} photo(s)")
        ids.size
    }

    suspend fun restoreFromTrash(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        runCatching { photoDao.restoreFromTrash(ids.toList()) }
            .onFailure { Log.w(TAG, "restoreFromTrash failed: ${it.message}") }
        Log.i(TAG, "restoreFromTrash: ${ids.size} photo(s)")
        ids.size
    }

    /**
     * Delete trashed photos for real — the same path the old immediate remove
     * used (row + thumbnail + caches, and the file itself only when the app owns
     * the copy). Used by the trash view's "Delete permanently" and by the
     * retention sweep.
     */
    suspend fun purge(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val ticket = stageRemove(ids)
        commitRemove(ticket)
        Log.i(TAG, "purge: ${ids.size} photo(s) deleted permanently")
        ids.size
    }

    suspend fun stageRemove(ids: Collection<Long>): RemovalTicket {
        val photos = mutableListOf<PhotoEntity>()
        val edits = mutableListOf<EditEntity>()
        val thumbs = mutableListOf<ThumbnailEntity>()
        for (photoId in ids) {
            val photo = photoDao.getById(photoId) ?: continue
            editDao.getByPhotoId(photoId)?.let(edits::add)
            thumbnailDao.getByPhotoId(photoId)?.let(thumbs::add)
            photos.add(photo)
            // Cascades the edit + thumbnail rows.
            runCatching { photoDao.delete(photo) }
                .onFailure { Log.w(TAG, "row delete of $photoId failed: ${it.message}") }
        }
        Log.i(TAG, "stageRemove: ${photos.size} rows removed (files deferred)")
        return RemovalTicket(photos, edits, thumbs)
    }

    /** Undo: re-insert the rows (same ids — files were never touched). */
    suspend fun undoRemove(ticket: RemovalTicket) {
        for (photo in ticket.photos) {
            runCatching { photoDao.insert(photo) }
                .onFailure { Log.w(TAG, "undo re-insert of ${photo.id} failed: ${it.message}") }
        }
        ticket.edits.forEach { runCatching { editDao.insert(it) } }
        ticket.thumbs.forEach { runCatching { thumbnailDao.insert(it) } }
        Log.i(TAG, "undoRemove: restored ${ticket.count} photos")
    }

    /**
     * The undo window closed — delete the app-owned files (Req 14.31, 16.17),
     * including the RAW editor's Stage A/B native decode cache and its
     * session-survival action-state file. Both are keyed by a SHA-256 of the
     * SOURCE FILE'S BYTES (see `RawV3Coordinator.sha256` / `RawV3Cache`), not
     * by photoId, so removal has to re-hash the file to find them — the same
     * cost the editor itself pays to look the cache up. Best-effort: an
     * unreachable or already-gone source just means there is nothing to find,
     * not a failure, so every step here is wrapped in `runCatching`.
     */
    suspend fun commitRemove(ticket: RemovalTicket) = withContext(Dispatchers.IO) {
        ticket.thumbs.forEach { deleteQuietly(it.path) }
        ticket.edits.forEach { edit ->
            // A fallback sidecar is app-owned and unreachable once the rows are
            // gone. A beside-original sidecar lives in the user's folder and
            // ALWAYS survives removal (Req 14.30/14.31).
            if (!edit.sidecarBesideOriginal) deleteQuietly(edit.sidecarPath)
        }
        ticket.photos.forEach { photo ->
            // Hash-and-purge the decode cache BEFORE deleting a Copied_Original
            // — purging needs to read the source bytes to find the cache key.
            purgeDecodeCache(photo)
            if (photo.sourceIsCopy) deleteQuietly(Uri.parse(photo.sourceUri).path)
        }
        Log.i(TAG, "commitRemove: files for ${ticket.count} photos deleted")
    }

    /**
     * Wipe the removed photo's RAW-editor scratch cache (`cache/raw_v3/<sha>/`
     * — Stage A tif, Stage B preview snapshot, its own sidecar mirror, U2Net
     * segmentation) and its sticky action-state file (`filesDir/raw_v3_state/
     * <sha>.bin`). Neither is reachable by photoId, and nothing else in the
     * app ever cleans them up for a removed photo (the cache is otherwise
     * only cleared by a new decode of the SAME file, or a manual "Clear
     * cache") — leaving them behind is a silent, permanent leak.
     */
    private fun purgeDecodeCache(photo: PhotoEntity) {
        val sha = runCatching {
            val uri = Uri.parse(photo.sourceUri)
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return

        val cache = RawV3Cache(context)
        runCatching { cache.purge(sha) }
            .onFailure { Log.w(TAG, "decode-cache purge failed for photo ${photo.id}: ${it.message}") }
        runCatching { cache.actionState(sha).delete() }
    }

    // ── File staging helpers ─────────────────────────────────────────────────

    /**
     * Copy a Copied_Original file into [destProjectId]'s `originals/` and
     * return the new `file://` URI string. Null when the photo is a normal
     * linked photo (nothing app-owned to relocate) or the source is unreadable.
     */
    private fun stageCopiedOriginal(photo: PhotoEntity, destProjectId: Long): String? {
        if (!photo.sourceIsCopy) return null
        val src = Uri.parse(photo.sourceUri).path?.let(::File) ?: return null
        if (!src.canRead()) return null
        val destDir = File(context.filesDir, "gallery/projects/$destProjectId/originals")
            .apply { mkdirs() }
        // Keep the original name when free; suffix with the timestamp otherwise.
        var dest = File(destDir, src.name)
        if (dest.exists()) {
            dest = File(destDir, "${System.currentTimeMillis()}_${src.name}")
        }
        src.copyTo(dest, overwrite = false)
        return Uri.fromFile(dest).toString()
    }

    /**
     * Copy a fallback sidecar into the destination project's `sidecars/` dir
     * (same photoId — moves keep row identity) and return the new path. Null
     * when there is nothing to relocate: no edit row, a beside-original sidecar
     * (lives with the photo file, valid from any project), or a path already
     * outside the source project's directory.
     */
    private fun stageFallbackSidecar(
        edit: EditEntity?,
        sourceProjectId: Long,
        destProjectId: Long,
        photoId: Long,
    ): String? {
        if (edit == null || edit.sidecarBesideOriginal) return null
        val src = File(edit.sidecarPath)
        if (!src.canRead()) return null
        val sourceRoot = File(context.filesDir, "gallery/projects/$sourceProjectId")
        if (!src.absolutePath.startsWith(sourceRoot.absolutePath)) return null
        val dest = File(
            File(context.filesDir, "gallery/projects/$destProjectId/sidecars").apply { mkdirs() },
            "$photoId.xmp",
        )
        src.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    /** Copy a sidecar file to `<dest>/sidecars/<newId>.xmp`; null when unreadable. */
    private fun copySidecarFile(sourcePath: String, destProjectId: Long, newId: Long): String? {
        val src = File(sourcePath)
        if (!src.canRead()) return null
        val dest = File(
            File(context.filesDir, "gallery/projects/$destProjectId/sidecars").apply { mkdirs() },
            "$newId.xmp",
        )
        return runCatching { src.copyTo(dest, overwrite = true); dest.absolutePath }.getOrNull()
    }

    /**
     * Copy a cached thumbnail file to `<dest>/thumbs/<photoId>.jpg` (Req 8.5a)
     * and return the new path. Null when there is no thumbnail or the file is
     * gone (row exists, file evicted-by-hand — treat as absent).
     */
    private fun stageThumbFile(
        thumb: ThumbnailEntity?,
        destProjectId: Long,
        photoId: Long,
    ): String? {
        if (thumb == null) return null
        val src = File(thumb.path)
        if (!src.canRead()) return null
        val dest = File(
            File(context.filesDir, "gallery/projects/$destProjectId/thumbs").apply { mkdirs() },
            "$photoId.jpg",
        )
        return runCatching { src.copyTo(dest, overwrite = true); dest.absolutePath }.getOrNull()
    }

    private fun deleteQuietly(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).delete() }
    }

    private companion object {
        const val TAG = "BatchOps"
    }
}
