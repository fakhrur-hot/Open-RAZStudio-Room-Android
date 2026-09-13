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
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarResolver
import java.io.File

/**
 * Sidecar location for a photo inside a Gallery Workspace project
 * (Requirements 4.1, 4.2, 4.5, 4.6, 4.10).
 *
 * Resolution order:
 *  1. A `<filename>.xmp` document beside the original, when a Folder_Write_Grant
 *     covers its directory. **Authoritative** — edits travel with the photos.
 *  2. `filesDir/gallery/projects/<projectId>/sidecars/<photoId>.xmp` otherwise.
 *
 * **Never `cacheDir`** (Requirement 4.10). The pre-existing store used the cache
 * for any non-`file://` source, which the OS may evict — acceptable for a one-off
 * file opened directly, unacceptable for a library photo whose edits are supposed
 * to persist.
 *
 * Created per open photo, since it is bound to one project and photo id.
 */
class ProjectSidecarResolver(
    private val context: Context,
    private val projectId: Long,
    private val photoId: Long,
    private val displayName: String,
    private val grants: FolderGrantManager,
    private val editDao: EditDao,
) : SidecarResolver {

    override suspend fun resolve(sourceUri: Uri): SidecarLocation {
        // A Copied_Original lives in app-private storage, so its sidecar sits
        // beside it with no grant needed at all (Requirement 16.15).
        if (sourceUri.scheme == "file") {
            val path = sourceUri.path
            if (path != null) {
                val source = File(path)
                if (source.parentFile?.canWrite() == true) {
                    return SidecarLocation.LocalFile(
                        file = File(source.parentFile, "${source.name}.xmp"),
                        besideOriginal = true,
                    )
                }
            }
        }

        grants.resolveOrCreateSidecarDoc(sourceUri, displayName)?.let { doc ->
            recordLocation(doc.toString(), besideOriginal = true)
            return SidecarLocation.SafDocument(doc)
        }

        val fallback = fallbackFile()
        recordLocation(fallback.absolutePath, besideOriginal = false)
        return SidecarLocation.LocalFile(fallback, besideOriginal = false)
    }

    /** `filesDir/gallery/projects/<id>/sidecars/<photoId>.xmp` — persistent, never cache. */
    fun fallbackFile(): File = File(
        File(context.filesDir, "gallery/projects/$projectId/sidecars"),
        "$photoId.xmp",
    )

    /**
     * Keeps `edits.isNeutral` honest (Requirement 15.18) and — as a side
     * effect — fixes a gap in [resolve]'s file:// fast path (a
     * Copied_Original's location never went through [recordLocation], so no
     * row existed until the FIRST write; upsert here instead of assuming one).
     */
    override suspend fun onSnapshotWritten(
        location: com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation,
        hasVisibleEdit: Boolean,
    ) {
        val path = when (location) {
            is com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation.LocalFile ->
                location.file.absolutePath
            is com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation.SafDocument ->
                location.documentUri.toString()
            com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation.None -> return
        }
        val neutral = !hasVisibleEdit
        runCatching {
            val now = System.currentTimeMillis()
            val existing = editDao.getByPhotoId(photoId)
            if (existing == null) {
                editDao.insert(
                    EditEntity(
                        photoId = photoId,
                        sidecarPath = path,
                        sidecarBesideOriginal = location.besideOriginal,
                        revisionCount = 1,
                        updatedAt = now,
                        isNeutral = neutral,
                    )
                )
            } else if (
                existing.isNeutral != neutral ||
                existing.sidecarPath != path ||
                existing.sidecarBesideOriginal != location.besideOriginal
            ) {
                editDao.update(
                    existing.copy(
                        sidecarPath = path,
                        sidecarBesideOriginal = location.besideOriginal,
                        isNeutral = neutral,
                        updatedAt = now,
                    )
                )
            }
        }.onFailure { Log.w(TAG, "onSnapshotWritten failed: ${it.message}") }
    }

    /**
     * Keep `edits` in step with where the sidecar actually went.
     *
     * The row is an INDEX of edits, never the store (design.md) — it records the
     * resolved location so the grid can show the "not saved to your folder"
     * indicator and so removal knows whether deleting the fallback is safe.
     */
    private suspend fun recordLocation(path: String, besideOriginal: Boolean) {
        runCatching {
            val existing = editDao.getByPhotoId(photoId)
            val now = System.currentTimeMillis()
            if (existing == null) {
                editDao.insert(
                    EditEntity(
                        photoId = photoId,
                        sidecarPath = path,
                        sidecarBesideOriginal = besideOriginal,
                        revisionCount = 0,
                        updatedAt = now,
                        // Safe default: this row can be created by a mere
                        // resolve() (e.g. an existence check before ANY write
                        // happens), so it must never claim edits it hasn't
                        // seen yet. onSnapshotWritten flips it false the
                        // moment a snapshot carrying a real edit is written.
                        isNeutral = true,
                    )
                )
            } else if (
                existing.sidecarPath != path ||
                existing.sidecarBesideOriginal != besideOriginal
            ) {
                editDao.update(
                    existing.copy(
                        sidecarPath = path,
                        sidecarBesideOriginal = besideOriginal,
                        updatedAt = now,
                    )
                )
            }
        }.onFailure { Log.w(TAG, "could not record sidecar location: ${it.message}") }
    }

    companion object {
        private const val TAG = "ProjectSidecarResolver"

        /**
         * Move fallback sidecars beside their originals once a grant arrives
         * (Requirement 4.8), reporting counts rather than doing it silently
         * (Requirement 4.8 / design.md) — relocating someone's edit history
         * without saying how much moved is exactly the kind of invisible bulk
         * operation that erodes trust.
         */
        suspend fun migrateFallbacks(
            context: Context,
            projectId: Long,
            grants: FolderGrantManager,
            photoDao: PhotoDao,
            editDao: EditDao,
        ): MigrationReport {
            var migrated = 0
            var skipped = 0
            var failed = 0

            for (photoId in photoDao.getAllIds(projectId)) {
                val edit = editDao.getByPhotoId(photoId) ?: continue
                if (edit.sidecarBesideOriginal) { skipped++; continue }

                val photo = photoDao.getById(photoId) ?: continue
                val from = File(edit.sidecarPath)
                if (!from.exists()) { skipped++; continue }

                val target = grants.resolveOrCreateSidecarDoc(
                    Uri.parse(photo.sourceUri),
                    photo.displayName,
                )
                if (target == null) { skipped++; continue }

                val ok = runCatching {
                    val text = from.readText()
                    context.contentResolver.openOutputStream(target, "wt")
                        ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    // Only drop the fallback once the new copy is written, so an
                    // interrupted migration loses nothing.
                    from.delete()
                    true
                }.getOrElse {
                    Log.w(TAG, "migrate failed for ${photo.displayName}: ${it.message}")
                    false
                }

                if (ok) {
                    editDao.update(
                        edit.copy(
                            sidecarPath = target.toString(),
                            sidecarBesideOriginal = true,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    migrated++
                } else {
                    failed++
                }
            }

            Log.i(
                TAG,
                "migrateFallbacks(project=$projectId): migrated=$migrated " +
                    "skipped=$skipped failed=$failed"
            )
            return MigrationReport(migrated, skipped, failed)
        }
    }
}
