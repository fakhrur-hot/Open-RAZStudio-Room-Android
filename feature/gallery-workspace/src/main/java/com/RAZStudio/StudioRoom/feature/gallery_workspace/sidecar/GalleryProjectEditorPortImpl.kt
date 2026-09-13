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
import android.util.Log
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.project.GalleryProjectEditorPort
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarResolver
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarXmpSerializer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gallery Workspace's implementation of the port [RawEditorComponent] uses to open a photo
 * carrying Project context (Requirement 9, 15). See [GalleryProjectEditorPort]'s doc for why
 * this lives here rather than photo-editor depending back on this module.
 */
@Singleton
class GalleryProjectEditorPortImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
    private val editDao: EditDao,
    private val grants: FolderGrantManager,
    private val sidecarOps: SidecarOpsRepository,
    private val thumbnailDao: ThumbnailDao,
) : GalleryProjectEditorPort {

    override suspend fun updateThumbnail(projectId: Long, photoId: Long, edited: android.graphics.Bitmap) {
        // Same geometry + path as ThumbnailWorker.generateThumbnail so the
        // worker's cap/eviction bookkeeping keeps working on this file.
        val longEdge = 640
        val scale = longEdge.toFloat() / maxOf(edited.width, edited.height).coerceAtLeast(1)
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
            edited,
            (edited.width * scale).toInt().coerceAtLeast(1),
            (edited.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else edited
        val dir = File(context.filesDir, "gallery/projects/$projectId/thumbs").apply { mkdirs() }
        val file = File(dir, "$photoId.jpg")
        val ok = runCatching {
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        }.getOrDefault(false)
        if (bmp !== edited) bmp.recycle()
        if (!ok) { Log.w(TAG, "updateThumbnail($photoId): write failed"); return }
        val now = System.currentTimeMillis()
        // REPLACE upsert; generatedAt = now is what invalidates the grid's cache key.
        thumbnailDao.insert(
            ThumbnailEntity(
                photoId = photoId,
                path = file.absolutePath,
                bytes = file.length(),
                generatedAt = now,
                lastAccessedAt = now,
            )
        )
        Log.i(TAG, "updateThumbnail($photoId): edited thumbnail ${file.length()}B → ${file.path}")
    }

    override fun resolverFor(projectId: Long, photoId: Long, displayName: String): SidecarResolver =
        ProjectSidecarResolver(context, projectId, photoId, displayName, grants, editDao)

    override suspend fun hasEditRecord(projectId: Long, photoId: Long): Boolean =
        editDao.getByPhotoId(photoId) != null

    override suspend fun createInitialSidecar(
        projectId: Long,
        photoId: Long,
        displayName: String,
        config: WorkspaceConfig,
    ) {
        // The source URI is needed for the file:// fast path and the SAF
        // sibling-naming; this runs for photos that may have no active editor
        // session (bulk apply reaches photos nobody has opened), so fetch it
        // rather than assuming a caller already has it.
        val sourceUri = photoDao.getById(photoId)?.sourceUri?.let(android.net.Uri::parse)
            ?: return
        val resolver = resolverFor(projectId, photoId, displayName)
        val snapshot = SidecarSnapshot(workspace = config, macro = UserMacro())
        val xmp = SidecarXmpSerializer.toXmp(snapshot)
        // Mirrors SidecarStore.writeSnapshot's resolve-then-write-then-report
        // shape, without needing a SidecarStore instance.
        val location = resolver.resolve(sourceUri)
        if (writeAt(location, xmp)) {
            resolver.onSnapshotWritten(location, hasVisibleEdit = false)
        }
    }

    override suspend fun applyLensProfileToMatching(
        projectId: Long,
        sourcePhotoId: Long,
        config: WorkspaceConfig,
    ): GalleryProjectEditorPort.MatchResult {
        val source = photoDao.getById(sourcePhotoId)
            ?: return GalleryProjectEditorPort.MatchResult(0, 0)
        val sourceKit = sidecarOps.probeKit(source)
        if (sourceKit.camera.isEmpty() && sourceKit.lens.isEmpty()) {
            // Nothing to match against — every candidate is "skipped", not
            // silently "covered" against an unknown kit.
            val total = photoDao.getUnedited(projectId).count { it.id != sourcePhotoId }
            return GalleryProjectEditorPort.MatchResult(0, total)
        }

        var covered = 0
        var skipped = 0
        for (candidate in photoDao.getUnedited(projectId)) {
            if (candidate.id == sourcePhotoId) continue
            val kit = sidecarOps.probeKit(candidate)
            val matches = kit.camera.equals(sourceKit.camera, ignoreCase = true) &&
                kit.lens.equals(sourceKit.lens, ignoreCase = true)
            if (!matches) {
                skipped++
                continue
            }
            runCatching {
                createInitialSidecar(projectId, candidate.id, candidate.displayName, config)
            }.onSuccess { covered++ }.onFailure {
                Log.w(TAG, "bulk lens apply failed for ${candidate.id}: ${it.message}")
                skipped++
            }
        }
        Log.i(TAG, "applyLensProfileToMatching($projectId): covered=$covered skipped=$skipped")
        return GalleryProjectEditorPort.MatchResult(covered, skipped)
    }

    /** Same write logic as [com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarStore]'s private writeAt. */
    private fun writeAt(
        location: com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation,
        xmp: String,
    ): Boolean = when (location) {
        is com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation.LocalFile ->
            runCatching {
                location.file.parentFile?.mkdirs()
                location.file.writeText(xmp)
                true
            }.getOrElse {
                Log.w(TAG, "writeAt failed: ${location.file}", it)
                false
            }
        is com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation.SafDocument ->
            runCatching {
                context.contentResolver.openOutputStream(location.documentUri, "wt")
                    ?.use { it.write(xmp.toByteArray(Charsets.UTF_8)) }
                true
            }.getOrElse {
                Log.w(TAG, "writeAt failed: ${location.documentUri}", it)
                false
            }
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarLocation.None -> false
    }

    private companion object {
        const val TAG = "GalleryProjectEditorPort"
    }
}
