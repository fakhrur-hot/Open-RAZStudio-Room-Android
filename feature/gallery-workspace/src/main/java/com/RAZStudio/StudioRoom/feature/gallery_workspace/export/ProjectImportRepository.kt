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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ProjectDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a project import (Requirement 12.5). */
data class ProjectImportResult(
    val projectId: Long?,
    val imported: Int,
    /** Photos recreated WITHOUT a resolvable source — surfaced via the Relink_Flow (Req 12.5). */
    val unreachable: Int,
    val failed: Int,
)

/**
 * Imports a Project bundle written by [ProjectExportRepository] (Requirement
 * 12.5). Recreates the project and every Photo_Entry with its rating, flag,
 * colour label, and Edit_Sidecar restored (into this device's per-project
 * fallback location — the export's own beside-original/fallback distinction
 * cannot travel across devices).
 *
 * A photo whose original was NOT included in the export (Requirement 12.4)
 * is recreated pointing at an unresolvable placeholder URI, `uriPermissionOk
 * = false`, but with its `displayName`/`sizeBytes`/`contentFingerprint`
 * intact — exactly what [com.RAZStudio.StudioRoom.feature.gallery_workspace.relink.RelinkCoordinator]
 * needs to rematch it later via the SAME precedence a directory relink uses
 * (Requirement 12.3a / 10.7a), by the user just pointing the existing
 * "Relink a folder" action (Task 10) at wherever the originals now live —
 * no separate rematch code needed here.
 */
@Singleton
class ProjectImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao,
    private val editDao: EditDao,
    private val thumbnailDao: ThumbnailDao,
) {

    suspend fun importProject(sourceTreeUri: Uri): ProjectImportResult = withContext(Dispatchers.IO) {
        val sourceDir = DocumentFile.fromTreeUri(context, sourceTreeUri)
            ?: return@withContext ProjectImportResult(null, 0, 0, 0)
        val projectMeta = readTextFile(sourceDir.findFile("project.txt"))
            ?: return@withContext ProjectImportResult(null, 0, 0, 0)
        val photosDir = sourceDir.findFile("photos")
            ?: return@withContext ProjectImportResult(null, 0, 0, 0)

        val now = System.currentTimeMillis()
        val projectName = projectMeta["name"]?.takeIf { it.isNotBlank() } ?: "Imported project"
        val minOrder = projectDao.getAll().first().minOfOrNull { it.sortOrder } ?: 0
        val projectId = runCatching {
            projectDao.insert(
                ProjectEntity(
                    name = projectName,
                    createdAt = now,
                    updatedAt = now,
                    coverPhotoId = null,
                    sortOrder = minOrder - 1,
                    gridSortMode = 0,
                    gridFilterMask = 0,
                    gridColumns = 3,
                )
            )
        }.getOrNull() ?: return@withContext ProjectImportResult(null, 0, 0, 0)
        val projectDir = File(context.filesDir, "gallery/projects/$projectId").apply { mkdirs() }

        val children = photosDir.listFiles().associateBy { it.name.orEmpty() }
        val manifestIndices = children.keys
            .mapNotNull { name -> Regex("^(\\d+)\\.txt$").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() }
            .sorted()

        var imported = 0
        var unreachable = 0
        var failed = 0

        for (index in manifestIndices) {
            runCatching {
                val fields = readTextFile(children["$index.txt"]) ?: return@runCatching

                val originalDoc = children.entries.firstOrNull {
                    it.key.startsWith("$index.original.")
                }?.value
                val sourceUri: String
                val sourceIsCopy: Boolean
                val uriPermissionOk: Boolean
                if (originalDoc != null) {
                    val ext = originalDoc.name.orEmpty().substringAfterLast('.', "bin")
                    val destFile = File(File(projectDir, "originals").apply { mkdirs() }, "$index.$ext")
                    val copied = copyToLocalFile(originalDoc, destFile)
                    if (copied) {
                        sourceUri = Uri.fromFile(destFile).toString()
                        sourceIsCopy = true
                        uriPermissionOk = true
                    } else {
                        sourceUri = "import-pending:///$projectId/$index"
                        sourceIsCopy = false
                        uriPermissionOk = false
                    }
                } else {
                    // No original was exported — this Photo_Entry is recreated
                    // unreachable on purpose (Req 12.5); Relink finds it later.
                    sourceUri = "import-pending:///$projectId/$index"
                    sourceIsCopy = false
                    uriPermissionOk = false
                }

                val photoId = photoDao.insert(
                    PhotoEntity(
                        projectId = projectId,
                        sourceUri = sourceUri,
                        displayName = fields["displayName"].orEmpty(),
                        mimeType = fields["mimeType"].orEmpty(),
                        sourceFormat = fields["sourceFormat"]?.toIntOrNull() ?: 0,
                        sizeBytes = fields["sizeBytes"]?.toLongOrNull() ?: 0L,
                        widthPx = fields["widthPx"]?.toIntOrNull() ?: 0,
                        heightPx = fields["heightPx"]?.toIntOrNull() ?: 0,
                        capturedAt = fields["capturedAt"]?.toLongOrNull()?.takeIf { it >= 0 },
                        addedAt = now,
                        uriPermissionOk = uriPermissionOk,
                        sourceIsCopy = sourceIsCopy,
                        contentFingerprint = fields["contentFingerprint"]?.ifBlank { null },
                        rating = fields["rating"]?.toIntOrNull() ?: 0,
                        flagState = fields["flagState"]?.toIntOrNull() ?: 0,
                        colorLabel = fields["colorLabel"]?.toIntOrNull() ?: 0,
                    )
                )
                if (!uriPermissionOk) unreachable++

                val thumbDoc = children["$index.jpg"]
                if (thumbDoc != null) {
                    val thumbFile = File(File(projectDir, "thumbs").apply { mkdirs() }, "$photoId.jpg")
                    if (copyToLocalFile(thumbDoc, thumbFile)) {
                        thumbnailDao.insert(
                            ThumbnailEntity(
                                photoId = photoId,
                                path = thumbFile.absolutePath,
                                bytes = thumbFile.length(),
                                generatedAt = now,
                                lastAccessedAt = now,
                            )
                        )
                    }
                }

                // Accept both "$index.xmp" (correct, octet-stream export) and
                // "$index.xmp.xml" (bundles written by the first build, whose
                // text/xml mime made the provider append ".xml" — see the
                // matching comment in ProjectExportRepository).
                val sidecarDoc = children["$index.xmp"] ?: children["$index.xmp.xml"]
                if (sidecarDoc != null) {
                    val sidecarFile = File(
                        File(projectDir, "sidecars").apply { mkdirs() }, "$photoId.xmp",
                    )
                    if (copyToLocalFile(sidecarDoc, sidecarFile)) {
                        editDao.insert(
                            EditEntity(
                                photoId = photoId,
                                sidecarPath = sidecarFile.absolutePath,
                                // Always re-created as a fallback: the export's own
                                // beside-original/fallback distinction cannot travel
                                // to a different device or folder layout.
                                sidecarBesideOriginal = false,
                                revisionCount = 0,
                                updatedAt = now,
                                isNeutral = false,
                            )
                        )
                    }
                }

                imported++
            }.onFailure {
                failed++
                Log.w(TAG, "import of photo index $index failed: ${it.message}")
            }
        }

        Log.i(
            TAG,
            "importProject -> project $projectId ('$projectName'): imported=$imported " +
                "unreachable=$unreachable failed=$failed",
        )
        ProjectImportResult(projectId, imported, unreachable, failed)
    }

    private fun copyToLocalFile(doc: DocumentFile, dest: File): Boolean = runCatching {
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } != null
    }.getOrDefault(false)

    private fun readTextFile(doc: DocumentFile?): Map<String, String>? {
        if (doc == null) return null
        return runCatching {
            context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { reader ->
                val fields = mutableMapOf<String, String>()
                reader.forEachLine { line ->
                    val i = line.indexOf('=')
                    if (i > 0) fields[line.substring(0, i)] = line.substring(i + 1)
                }
                fields
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "ProjectImport"
    }
}
