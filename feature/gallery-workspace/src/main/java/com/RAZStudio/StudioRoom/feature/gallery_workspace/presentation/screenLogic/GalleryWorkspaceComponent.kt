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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic

import android.content.Context
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ProjectDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.gallery_workspace.export.ProjectExportRepository
import com.RAZStudio.StudioRoom.feature.gallery_workspace.export.ProjectExportResult
import com.RAZStudio.StudioRoom.feature.gallery_workspace.export.ProjectImportRepository
import com.RAZStudio.StudioRoom.feature.gallery_workspace.export.ProjectImportResult
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.ImportResult
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoImportRepository
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.FolderGrantManager
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.MigrationReport
import com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.ProjectSidecarResolver
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.decompose.ComponentContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.File

/**
 * One row of the project list: the stored project plus the derived counts the UI
 * needs. Kept as a view model rather than exposing [ProjectEntity] directly so a
 * schema change does not ripple into the composables.
 */
data class ProjectRow(
    val id: Long,
    val name: String,
    val photoCount: Int,
    /** Absolute path of the cover thumbnail, or null when there is nothing to show. */
    val coverThumbPath: String?,
)

/**
 * Gallery Workspace project list (Requirements 1 and 2).
 *
 * Owns project lifecycle only. Photo import, the grid, and the editor round-trip
 * live behind [ProjectComponent] so this class stays small and the project list
 * never has to know how a photo is stored.
 */
class GalleryWorkspaceComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao,
    private val thumbnailDao: ThumbnailDao,
    private val importRepository: PhotoImportRepository,
    private val editDao: EditDao,
    private val folderGrants: FolderGrantManager,
    private val projectExportRepository: ProjectExportRepository,
    private val projectImportRepository: ProjectImportRepository,
    private val photoExporter: com.RAZStudio.StudioRoom.feature.gallery_workspace
        .export.ProjectPhotoExporter,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    // ── Storage ─────────────────────────────────────────────────────────────

    /** Byte breakdown behind the storage bar on the project list. */
    data class StorageStats(
        val thumbnailBytes: Long = 0,
        val copiedOriginalBytes: Long = 0,
        val cacheBytes: Long = 0,
        val linkedSourceBytes: Long = 0,
        val deviceFreeBytes: Long = 0,
        val deviceTotalBytes: Long = 0,
    ) {
        /** What removing app data would actually reclaim. */
        val appBytes: Long get() = thumbnailBytes + copiedOriginalBytes + cacheBytes
    }

    private val _storage = MutableStateFlow(StorageStats())
    val storage: StateFlow<StorageStats> = _storage.asStateFlow()

    /**
     * Recompute the storage breakdown. Thumbnail and copied-original totals come
     * from the DB columns (never by walking the tree — that cost scales with the
     * library); only the decode caches, which no table tracks, are measured on
     * disk, and those are shallow.
     */
    fun refreshStorage() {
        componentScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stat = runCatching {
                val fs = android.os.StatFs(context.filesDir.absolutePath)
                fs.availableBytes to fs.totalBytes
            }.getOrDefault(0L to 0L)
            _storage.value = StorageStats(
                thumbnailBytes = runCatching { thumbnailDao.getTotalBytesNow() }.getOrDefault(0L),
                copiedOriginalBytes = runCatching { photoDao.copiedOriginalsBytes() }.getOrDefault(0L),
                cacheBytes = listOf("raw_v3", "raw_pipeline", "verify_lastsave")
                    .sumOf { dirBytes(java.io.File(context.cacheDir, it)) },
                linkedSourceBytes = runCatching { photoDao.linkedSourceBytes() }.getOrDefault(0L),
                deviceFreeBytes = stat.first,
                deviceTotalBytes = stat.second,
            )
        }
    }

    /** Drop the RAW decode caches. Thumbnails and sidecars are left alone. */
    fun clearDecodeCache() {
        componentScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            listOf("raw_v3", "raw_pipeline", "verify_lastsave").forEach {
                runCatching { java.io.File(context.cacheDir, it).deleteRecursively() }
            }
            refreshStorage()
            _error.value = context.getString(com.RAZStudio.StudioRoom.core.resources.R.string.gallery_storage_cache_cleared)
        }
    }

    private fun dirBytes(dir: java.io.File): Long = runCatching {
        if (!dir.exists()) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    // ── Export photos (bake a project's sidecars to finished files) ──────────

    val photoExportState get() = photoExporter.state
    val photoExportRunning: Boolean get() = photoExporter.isRunning

    fun exportPhotos(
        projectId: Long,
        format: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat,
        watermarkPresetName: String?,
        onEmpty: () -> Unit,
    ) = photoExporter.start(projectId, format, watermarkPresetName, onEmpty)

    fun cancelPhotoExport() = photoExporter.cancel()
    fun dismissPhotoExport() = photoExporter.dismiss()

    /**
     * Projects with their photo counts and cover thumbnails.
     *
     * The cover falls back to the earliest-added photo's thumbnail when
     * `coverPhotoId` is unset (Requirement 2.10), which is why this needs a
     * per-project lookup rather than a single join.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val projects: StateFlow<List<ProjectRow>> = projectDao.getAll()
        .flatMapLatest { entities ->
            if (entities.isEmpty()) {
                flowOf(emptyList())
            } else {
                // One count flow per project, recombined. Fine at project-list
                // scale (tens), and it keeps counts live without polling.
                combine(entities.map { photoDao.getPhotoCount(it.id) }) { counts ->
                    entities.mapIndexed { index, entity ->
                        ProjectRow(
                            id = entity.id,
                            name = entity.name,
                            photoCount = counts[index],
                            coverThumbPath = null,
                        )
                    }
                }
            }
        }
        .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cover thumbnails resolved off the main thread; keyed by project id. */
    private val _covers = MutableStateFlow<Map<Long, String>>(emptyMap())
    val covers: StateFlow<Map<Long, String>> = _covers.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Last import outcome, for the "N added, M skipped" report (Req 3.7). */
    private val _lastImport = MutableStateFlow<ImportResult?>(null)
    val lastImport: StateFlow<ImportResult?> = _lastImport.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Last fallback-migration outcome, surfaced rather than done silently (Req 4.8). */
    private val _lastMigration = MutableStateFlow<MigrationReport?>(null)
    val lastMigration: StateFlow<MigrationReport?> = _lastMigration.asStateFlow()

    /** Tree grants revoked while the app was away (Req 4.4 / 4.6). */
    private val _revokedGrants = MutableStateFlow<List<String>>(emptyList())
    val revokedGrants: StateFlow<List<String>> = _revokedGrants.asStateFlow()

    /** Grants held when we last looked, for the resume diff. */
    private var heldGrants: Set<android.net.Uri> = emptySet()

    init {
        // Recompute covers whenever the project set changes.
        componentScope.launch {
            projects.collect { rows -> refreshCovers(rows.map { it.id }) }
        }

        heldGrants = folderGrants.heldTreeGrants()

        // Android gives NO callback for a revoked URI grant — a user can withdraw
        // folder access in system settings and the app only finds out when a
        // write fails. Re-checking on resume moves that discovery before the
        // user makes an edit that would otherwise be lost at write time.
        lifecycle.doOnResume {
            componentScope.launch {
                val lost = withContext(ioDispatcher) { folderGrants.revalidate(heldGrants) }
                heldGrants = withContext(ioDispatcher) { folderGrants.heldTreeGrants() }
                if (lost.isNotEmpty()) _revokedGrants.value = lost.map { it.toString() }
            }
        }
    }

    private suspend fun refreshCovers(projectIds: List<Long>) {
        val resolved = mutableMapOf<Long, String>()
        for (id in projectIds) {
            val entity = projectDao.getById(id).first() ?: continue
            val photoId = entity.coverPhotoId
                ?: photoDao.getEarliestAddedId(id)
                ?: continue
            val thumb = thumbnailDao.getByPhotoId(photoId) ?: continue
            if (File(thumb.path).exists()) resolved[id] = thumb.path
        }
        _covers.value = resolved
    }

    /**
     * Create a project. Returns false when [name] is blank (Requirement 2.2);
     * duplicate names are permitted (Requirement 2.3) and distinguished by id.
     *
     * New projects sort first, matching the expectation that the thing you just
     * made is the thing you want to open.
     */
    fun createProject(name: String, onCreated: (Long) -> Unit = {}) {
        if (name.isBlank()) return
        componentScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val minOrder = projectDao.getAll().first().minOfOrNull { it.sortOrder } ?: 0
                projectDao.insert(
                    ProjectEntity(
                        name = name.trim(),
                        createdAt = now,
                        updatedAt = now,
                        coverPhotoId = null,
                        sortOrder = minOrder - 1,
                        gridSortMode = 0,
                        gridFilterMask = 0,
                        gridColumns = DEFAULT_GRID_COLUMNS,
                    )
                )
            }.onSuccess { id ->
                projectDirFor(id).mkdirs()
                onCreated(id)
            }.onFailure { _error.value = it.message }
        }
    }

    fun renameProject(projectId: Long, newName: String) {
        if (newName.isBlank()) return
        componentScope.launch {
            val entity = projectDao.getById(projectId).first() ?: return@launch
            projectDao.update(
                entity.copy(name = newName.trim(), updatedAt = System.currentTimeMillis())
            )
        }
    }

    /**
     * Persist a new ordering (Requirement 2.5). Takes the full ordered id list
     * rather than a from/to pair so a drag that moves several positions writes
     * one consistent result instead of a sequence of swaps.
     */
    fun reorderProjects(orderedIds: List<Long>) {
        componentScope.launch {
            val byId = projectDao.getAll().first().associateBy { it.id }
            orderedIds.forEachIndexed { index, id ->
                byId[id]?.let { projectDao.update(it.copy(sortOrder = index)) }
            }
        }
    }

    /**
     * Delete a project and everything the app owns for it (Requirement 2.7).
     *
     * Photo, edit, and thumbnail rows go via the foreign-key cascade. The
     * project directory is removed explicitly, which also removes copied
     * originals and fallback sidecars.
     *
     * Originals stored elsewhere and sidecars written beside them are NOT
     * touched (Requirement 2.8) — they belong to the user, not to this project.
     */
    fun deleteProject(projectId: Long) {
        componentScope.launch {
            val entity = projectDao.getById(projectId).first() ?: return@launch
            projectDao.delete(entity)
            runCatching { projectDirFor(projectId).deleteRecursively() }
                .onFailure { _error.value = it.message }
        }
    }

    /** Assign a cover photo explicitly (Requirement 2.11). */
    fun setCoverPhoto(projectId: Long, photoId: Long?) {
        componentScope.launch {
            val entity = projectDao.getById(projectId).first() ?: return@launch
            projectDao.update(
                entity.copy(coverPhotoId = photoId, updatedAt = System.currentTimeMillis())
            )
        }
    }

    /**
     * Import picked documents into [projectId] (Requirements 3.2-3.7).
     *
     * Runs on the IO dispatcher: it touches the ContentResolver, reads EXIF, and
     * hashes two 64 KB windows per file, none of which belongs on the main
     * thread even though none of it decodes a frame.
     */
    fun importPhotos(projectId: Long, uris: List<Uri>) {
        if (uris.isEmpty()) return
        componentScope.launch {
            _importing.value = true
            runCatching {
                withContext(ioDispatcher) { importRepository.import(projectId, uris) }
            }.onSuccess { result ->
                _lastImport.value = result
                refreshCovers(projects.value.map { it.id })
            }.onFailure { _error.value = it.message }
            _importing.value = false
        }
    }

    fun consumeLastImport() { _lastImport.value = null }

    /** Intent for the folder-grant request (Requirement 4.4). */
    fun folderGrantIntent(): android.content.Intent = folderGrants.buildTreeRequestIntent()

    /**
     * Persist a folder grant and migrate any fallback sidecars in [projectId]
     * beside their originals (Requirements 4.4, 4.8).
     */
    fun onFolderGranted(projectId: Long, treeUri: android.net.Uri) {
        componentScope.launch {
            val report = withContext(ioDispatcher) {
                if (!folderGrants.persistTreeGrant(treeUri)) return@withContext null
                ProjectSidecarResolver.migrateFallbacks(
                    context = context,
                    projectId = projectId,
                    grants = folderGrants,
                    photoDao = photoDao,
                    editDao = editDao,
                )
            }
            heldGrants = withContext(ioDispatcher) { folderGrants.heldTreeGrants() }
            if (report != null) _lastMigration.value = report
            else _error.value = context.getString(
                com.RAZStudio.StudioRoom.core.resources.R.string.gallery_grant_keep_failed
            )
        }
    }

    fun consumeLastMigration() { _lastMigration.value = null }

    fun consumeRevokedGrants() { _revokedGrants.value = emptyList() }

    fun consumeError() { _error.value = null }

    // ── Project export / import (Requirement 12) ─────────────────────────────

    /** Intent for picking a destination directory to export into, or a source
     *  directory to import from — both are a plain SAF tree pick. */
    fun projectTransferDirIntent(): android.content.Intent = folderGrants.buildTreeRequestIntent()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _lastExport = MutableStateFlow<ProjectExportResult?>(null)
    val lastExport: StateFlow<ProjectExportResult?> = _lastExport.asStateFlow()

    /** Export [projectId] into the directory the user just picked (Req 12.1, 12.2, 12.4). */
    fun exportProject(projectId: Long, destTreeUri: android.net.Uri, includeOriginals: Boolean) {
        componentScope.launch {
            _exporting.value = true
            runCatching {
                withContext(ioDispatcher) {
                    projectExportRepository.exportProject(projectId, destTreeUri, includeOriginals)
                }
            }.onSuccess { _lastExport.value = it }
                .onFailure { _error.value = it.message }
            _exporting.value = false
        }
    }

    fun consumeLastExport() { _lastExport.value = null }

    private val _importingProject = MutableStateFlow(false)
    val importingProject: StateFlow<Boolean> = _importingProject.asStateFlow()

    private val _lastProjectImport = MutableStateFlow<ProjectImportResult?>(null)
    val lastProjectImport: StateFlow<ProjectImportResult?> = _lastProjectImport.asStateFlow()

    /**
     * Import a project bundle from the directory the user just picked (Req
     * 12.5). Any photo without an included original comes back unreachable —
     * the grid's existing Relink flow (Task 10) is how the user points them at
     * wherever the files now live, matching by the same fingerprint/name+size
     * precedence a directory relink already uses (Req 12.3a).
     */
    fun importProject(sourceTreeUri: android.net.Uri) {
        componentScope.launch {
            _importingProject.value = true
            runCatching {
                withContext(ioDispatcher) { projectImportRepository.importProject(sourceTreeUri) }
            }.onSuccess { result ->
                _lastProjectImport.value = result
                if (result.projectId != null) refreshCovers(projects.value.map { it.id } + result.projectId)
            }.onFailure { _error.value = it.message }
            _importingProject.value = false
        }
    }

    fun consumeLastProjectImport() { _lastProjectImport.value = null }

    /** `filesDir/gallery/projects/<id>` — see design.md storage layout. */
    private fun projectDirFor(projectId: Long): File =
        File(context.filesDir, "gallery/projects/$projectId")

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): GalleryWorkspaceComponent
    }

    companion object {
        const val DEFAULT_GRID_COLUMNS = 3
    }
}
