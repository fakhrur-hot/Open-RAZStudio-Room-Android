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
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a project export (Requirement 12.1, 12.2). */
data class ProjectExportResult(
    val exportedPhotos: Int,
    val withSidecar: Int,
    val withThumbnail: Int,
    val withOriginal: Int,
    val failed: Int,
)

/**
 * Exports a whole Project — its metadata, per-photo Edit_Sidecars, ratings/
 * flags/labels, and thumbnails — to a directory the user picks (Requirement
 * 12.1, 12.2). Original photo files are copied only when [ProjectExportRepository.exportProject]'s
 * `includeOriginals` is true (Requirement 12.4); every Photo_Entry always
 * records its filename, byte size, and Content_Fingerprint (Requirement
 * 12.3) so [ProjectImportRepository] — or a manual directory relink after
 * import — can rematch it later using the same precedence as a directory
 * relink (Requirement 10.7a / 12.3a).
 *
 * Hand-rolled `key=value` text files rather than JSON: this module has no
 * JSON dependency, the manifest is a dozen scalar fields per photo, and the
 * same format already proved out for the Stage A reopen-cache fix
 * (RawV3Coordinator's `CachedStageAMeta`).
 */
@Singleton
class ProjectExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao,
    private val editDao: EditDao,
    private val thumbnailDao: ThumbnailDao,
) {

    suspend fun exportProject(
        projectId: Long,
        destTreeUri: Uri,
        includeOriginals: Boolean,
    ): ProjectExportResult = withContext(Dispatchers.IO) {
        val project = projectDao.getById(projectId).first()
            ?: return@withContext ProjectExportResult(0, 0, 0, 0, 0)
        val destRoot = DocumentFile.fromTreeUri(context, destTreeUri)
            ?: return@withContext ProjectExportResult(0, 0, 0, 0, 0)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val folderName = "${sanitize(project.name)}_export_$stamp"
        val exportDir = destRoot.createDirectory(folderName)
            ?: return@withContext ProjectExportResult(0, 0, 0, 0, 0)
        val photosDir = exportDir.createDirectory("photos")
            ?: return@withContext ProjectExportResult(0, 0, 0, 0, 0)

        val photos = photoDao.getAllForProject(projectId)
        writeTextFile(exportDir, "project.txt") {
            appendLine("schemaVersion=1")
            appendLine("name=${project.name}")
            appendLine("exportedAtMs=${System.currentTimeMillis()}")
            appendLine("photoCount=${photos.size}")
            appendLine("includesOriginals=$includeOriginals")
        }

        var withSidecar = 0
        var withThumbnail = 0
        var withOriginal = 0
        var failed = 0

        photos.forEachIndexed { index, photo ->
            runCatching {
                val edit = editDao.getByPhotoId(photo.id)
                val thumb = thumbnailDao.getByPhotoId(photo.id)

                // MIME must be octet-stream, NOT text/xml: a DocumentsProvider
                // appends the canonical extension for a known mime, so text/xml
                // turned "0.xmp" into "0.xmp.xml" on a real device (caught live
                // 2026-08-28) — which the import's exact-name lookup then missed,
                // silently dropping every sidecar. octet-stream has no canonical
                // extension, so the name is kept verbatim. Same trap and fix as
                // FolderGrantManager.resolveOrCreateSidecarDoc.
                val hasSidecar = edit != null && copyInto(
                    openSidecarInput(edit.sidecarPath), photosDir, "$index.xmp",
                    "application/octet-stream",
                )
                val hasThumbnail = thumb != null && copyInto(
                    runCatching { java.io.File(thumb.path).inputStream() }.getOrNull(),
                    photosDir, "$index.jpg", "image/jpeg",
                )
                val hasOriginal = includeOriginals && copyInto(
                    runCatching {
                        context.contentResolver.openInputStream(Uri.parse(photo.sourceUri))
                    }.getOrNull(),
                    photosDir,
                    "$index.original.${extensionOf(photo.displayName)}",
                    photo.mimeType.ifBlank { "application/octet-stream" },
                )

                writeTextFile(photosDir, "$index.txt") {
                    appendLine("displayName=${photo.displayName}")
                    appendLine("mimeType=${photo.mimeType}")
                    appendLine("sourceFormat=${photo.sourceFormat}")
                    appendLine("sizeBytes=${photo.sizeBytes}")
                    appendLine("widthPx=${photo.widthPx}")
                    appendLine("heightPx=${photo.heightPx}")
                    appendLine("capturedAt=${photo.capturedAt ?: -1}")
                    appendLine("rating=${photo.rating}")
                    appendLine("flagState=${photo.flagState}")
                    appendLine("colorLabel=${photo.colorLabel}")
                    appendLine("contentFingerprint=${photo.contentFingerprint.orEmpty()}")
                    appendLine("hasSidecar=$hasSidecar")
                    appendLine("hasThumbnail=$hasThumbnail")
                    appendLine("hasOriginal=$hasOriginal")
                }

                if (hasSidecar) withSidecar++
                if (hasThumbnail) withThumbnail++
                if (hasOriginal) withOriginal++
            }.onFailure {
                failed++
                Log.w(TAG, "export of photo ${photo.id} failed: ${it.message}")
            }
        }

        Log.i(
            TAG,
            "exportProject $projectId -> $folderName: ${photos.size} photos, " +
                "sidecar=$withSidecar thumb=$withThumbnail original=$withOriginal failed=$failed",
        )
        ProjectExportResult(photos.size, withSidecar, withThumbnail, withOriginal, failed)
    }

    /** Sidecar location is either a `content://` URI (beside-original via SAF) or a plain filesystem path. */
    private fun openSidecarInput(sidecarPath: String): InputStream? = runCatching {
        if (sidecarPath.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(sidecarPath))
        } else {
            java.io.File(sidecarPath).takeIf { it.canRead() }?.inputStream()
        }
    }.getOrNull()

    /** Copies [input] into a new file under [dir]; returns whether anything was written. */
    private fun copyInto(input: InputStream?, dir: DocumentFile, name: String, mime: String): Boolean {
        if (input == null) return false
        return input.use { src ->
            val doc = dir.createFile(mime, name) ?: return false
            runCatching {
                context.contentResolver.openOutputStream(doc.uri)?.use { out -> src.copyTo(out) }
                    ?: return false
                true
            }.getOrElse {
                runCatching { doc.delete() }
                false
            }
        }
    }

    private fun writeTextFile(dir: DocumentFile, name: String, body: StringBuilder.() -> Unit) {
        val doc = dir.createFile("text/plain", name) ?: return
        val text = buildString(body)
        context.contentResolver.openOutputStream(doc.uri)?.use { it.write(text.toByteArray()) }
    }

    private fun extensionOf(displayName: String): String =
        displayName.substringAfterLast('.', "").ifBlank { "bin" }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "project" }

    private companion object {
        const val TAG = "ProjectExport"
    }
}
