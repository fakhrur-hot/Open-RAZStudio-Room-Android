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
import android.util.Log
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an import, for the "N added, M skipped" report (Requirement 3.7). */
data class ImportResult(
    val added: Int,
    val skipped: Int,
    /** Imported but with no persistable grant — will need relinking (Req 3.3). */
    val withoutPermission: Int,
)

/**
 * Inserts picked documents into a project (Requirements 3.2–3.9).
 *
 * Separated from [PhotoImporter], which owns only the picker intent, so this
 * logic is reachable without a Composable scope and testable without a UI.
 */
@Singleton
class PhotoImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
) {

    /**
     * Import [uris] into [projectId].
     *
     * Order matters: the persistable grant is taken BEFORE the row is inserted
     * (Requirement 3.2), so a row never exists claiming access the app does not
     * hold.
     */
    suspend fun import(projectId: Long, uris: List<Uri>): ImportResult {
        var added = 0
        var skipped = 0
        var withoutPermission = 0

        for (uri in uris) {
            val uriString = uri.toString()

            // Requirement 3.5 — same URI already in THIS project. Note the same
            // photo may live in several projects (3.6), so the check is scoped.
            if (photoDao.getBySourceUri(projectId, uriString) != null) {
                skipped++
                continue
            }

            val permissionOk = takePersistableGrant(uri)
            if (!permissionOk) withoutPermission++

            val meta = PhotoMetadataProbe.probe(context.contentResolver, uri)
            val fingerprint = ContentFingerprint.compute(
                resolver = context.contentResolver,
                uri = uri,
                sizeBytes = meta.sizeBytes,
            )

            runCatching {
                photoDao.insert(
                    PhotoEntity(
                        projectId = projectId,
                        sourceUri = uriString,
                        displayName = meta.displayName,
                        mimeType = meta.mimeType,
                        sourceFormat = meta.format.ordinal,
                        sizeBytes = meta.sizeBytes,
                        widthPx = meta.widthPx,
                        heightPx = meta.heightPx,
                        capturedAt = meta.capturedAt,
                        addedAt = System.currentTimeMillis(),
                        // Requirement 3.3 — import anyway, flag it, and let the
                        // relink flow surface it. Discarding the photo would be
                        // worse: the user picked it deliberately.
                        uriPermissionOk = permissionOk,
                        sourceIsCopy = false,
                        contentFingerprint = fingerprint,
                        stackKey = com.RAZStudio.StudioRoom.core.database.model
                            .StackKeys.of(meta.displayName),
                        cameraMake = meta.cameraMake,
                        cameraModel = meta.cameraModel,
                        lensModel = meta.lensModel,
                        iso = meta.iso,
                        apertureF = meta.apertureF,
                        shutterSpeed = meta.shutterSpeed,
                        focalLengthMm = meta.focalLengthMm,
                        gpsLat = meta.gpsLat,
                        gpsLon = meta.gpsLon,
                        rating = 0,
                        flagState = 0,
                        colorLabel = 0,
                    )
                )
            }.onSuccess {
                added++
            }.onFailure {
                // The unique (projectId, sourceUri) index can still reject a row
                // if the same URI appeared twice in one selection.
                Log.w(TAG, "insert failed for ${meta.displayName}: ${it.message}")
                skipped++
            }
        }

        Log.i(
            TAG,
            "import into project $projectId: added=$added skipped=$skipped " +
                "withoutPermission=$withoutPermission"
        )
        return ImportResult(added, skipped, withoutPermission)
    }

    /**
     * Persist read (and write where offered) access to [uri].
     *
     * Write is requested because Requirement 3.2 asks for it, but its absence is
     * NOT a failure: many providers grant read-only, and a read-only original is
     * perfectly usable — the sidecar has its own location contract and does not
     * depend on writing the original. So this falls back to read-only rather
     * than rejecting the photo.
     */
    private fun takePersistableGrant(uri: Uri): Boolean {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, readWrite)
            return true
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            return true
        }.onFailure {
            // Expected for share-received URIs, which are not persistable at all
            // (see Requirement 16.9). Not an error worth surfacing here.
            Log.i(TAG, "no persistable grant for $uri: ${it.message}")
        }
        return false
    }

    private companion object {
        const val TAG = "PhotoImportRepo"
    }
}
