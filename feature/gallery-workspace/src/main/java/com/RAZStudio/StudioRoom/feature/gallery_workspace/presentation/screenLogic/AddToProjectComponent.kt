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
import android.net.Uri
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ProjectDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.ImportPlan
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.SharedImportManager
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.SharedImportOutcome
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the UI is currently being asked to decide. */
sealed interface AddToProjectStage {
    data object Choosing : AddToProjectStage
    /** Copy needed; the disclosure must be shown before anything is written. */
    data class Disclose(val plan: ImportPlan, val projectName: String) : AddToProjectStage
    data object Working : AddToProjectStage
    data class Error(val message: String) : AddToProjectStage
}

/**
 * "Add to project" from the share-flow preview (Requirement 16).
 *
 * Lives here rather than in feature/image-preview so that module does not gain a
 * Room dependency purely to list projects (Requirement 16.24).
 */
class AddToProjectComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @Assisted("uris") val incomingUris: List<Uri>,
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao,
    private val thumbnailDao: ThumbnailDao,
    private val sharedImport: SharedImportManager,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    /** Index of the shared photo currently previewed; the default add target. */
    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    /** When true, add every shared photo rather than just the previewed one (Req 16.6). */
    private val _addAll = MutableStateFlow(false)
    val addAll: StateFlow<Boolean> = _addAll.asStateFlow()

    private val _stage = MutableStateFlow<AddToProjectStage>(AddToProjectStage.Choosing)
    val stage: StateFlow<AddToProjectStage> = _stage.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val projects: StateFlow<List<ProjectRow>> = projectDao.getAll()
        .flatMapLatest { entities ->
            if (entities.isEmpty()) flowOf(emptyList())
            else combine(entities.map { photoDao.getPhotoCount(it.id) }) { counts ->
                entities.mapIndexed { i, e ->
                    ProjectRow(e.id, e.name, counts[i], null)
                }
            }
        }
        .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _covers = MutableStateFlow<Map<Long, String>>(emptyMap())
    val covers: StateFlow<Map<Long, String>> = _covers.asStateFlow()

    init {
        componentScope.launch {
            projects.collect { rows ->
                val resolved = mutableMapOf<Long, String>()
                for (row in rows) {
                    val entity = projectDao.getById(row.id).first() ?: continue
                    val photoId = entity.coverPhotoId
                        ?: photoDao.getEarliestAddedId(row.id) ?: continue
                    val thumb = thumbnailDao.getByPhotoId(photoId) ?: continue
                    if (File(thumb.path).exists()) resolved[row.id] = thumb.path
                }
                _covers.value = resolved
            }
        }

        // Requirement 16.13d — sweep copies orphaned by a kill between the copy
        // completing and the row committing.
        componentScope.launch {
            withContext(ioDispatcher) {
                sharedImport.reapOrphanedCopies(projectDao.getAll().first().map { it.id })
            }
        }
    }

    fun setSelectedIndex(index: Int) { _selectedIndex.value = index }

    fun setAddAll(value: Boolean) { _addAll.value = value }

    /** URIs this add will act on: all shared, or just the previewed one. */
    private fun targetUris(): List<Uri> =
        if (_addAll.value) incomingUris
        else listOfNotNull(incomingUris.getOrNull(_selectedIndex.value))

    /**
     * Suggested name for inline project creation (Requirement 16.3a):
     * "Shared photos <today>", locale-formatted per Requirement 13.2.
     */
    fun suggestedProjectName(formattedDate: String, template: (String) -> String): String =
        template(formattedDate)

    fun createProjectAndAdd(name: String, formattedFallback: String) {
        val effective = name.ifBlank { formattedFallback }
        if (effective.isBlank()) return
        componentScope.launch {
            val id = runCatching {
                val now = System.currentTimeMillis()
                val minOrder = projectDao.getAll().first().minOfOrNull { it.sortOrder } ?: 0
                projectDao.insert(
                    ProjectEntity(
                        name = effective.trim(),
                        createdAt = now,
                        updatedAt = now,
                        coverPhotoId = null,
                        sortOrder = minOrder - 1,
                        gridSortMode = 0,
                        gridFilterMask = 0,
                        gridColumns = GalleryWorkspaceComponent.DEFAULT_GRID_COLUMNS,
                    )
                )
            }.getOrNull() ?: run {
                _stage.value = AddToProjectStage.Error(
                    context.getString(
                        com.RAZStudio.StudioRoom.core.resources.R.string.gallery_create_failed
                    )
                )
                return@launch
            }
            File(context.filesDir, "gallery/projects/$id").mkdirs()
            chooseProject(id, effective.trim())
        }
    }

    /**
     * The user picked a project. Plans first: when a copy is required the
     * disclosure must be shown BEFORE anything is written (Requirement 16.11).
     */
    fun chooseProject(projectId: Long, projectName: String) {
        componentScope.launch {
            _stage.value = AddToProjectStage.Working
            val plan = withContext(ioDispatcher) {
                sharedImport.plan(projectId, targetUris())
            }
            when {
                plan.insufficientStorage ->
                    _stage.value = AddToProjectStage.Error(
                        "no-space:${plan.shortfallBytes}"
                    )
                plan.needsDisclosure ->
                    _stage.value = AddToProjectStage.Disclose(plan, projectName)
                else -> runPlan(plan, projectName)
            }
        }
    }

    /** Disclosure accepted (Requirement 16.11). */
    fun confirmDisclosure() {
        val current = _stage.value as? AddToProjectStage.Disclose ?: return
        componentScope.launch { runPlan(current.plan, current.projectName) }
    }

    /**
     * Disclosure declined (Requirement 16.12). No Photo_Entry is created — a row
     * that is guaranteed to break on the next cold start is worse than not
     * adding the photo at all.
     */
    fun declineDisclosure() {
        _stage.value = AddToProjectStage.Error("declined")
    }

    fun dismissError() { _stage.value = AddToProjectStage.Choosing }

    private suspend fun runPlan(plan: ImportPlan, projectName: String) {
        _stage.value = AddToProjectStage.Working
        val outcome = withContext(ioDispatcher) { sharedImport.execute(plan) }
        when (outcome) {
            is SharedImportOutcome.Added -> navigateToProject(
                projectId = plan.projectId,
                revealPhotoId = outcome.photoIds.firstOrNull(),
            )
            is SharedImportOutcome.AllDuplicates -> navigateToProject(
                // Requirement 16.7 — skip the insert but still navigate and
                // reveal the existing entry rather than reporting an error.
                projectId = plan.projectId,
                revealPhotoId = outcome.existingPhotoIds.firstOrNull(),
            )
            is SharedImportOutcome.Failed ->
                _stage.value = AddToProjectStage.Error(outcome.reason)
        }
    }

    private fun navigateToProject(projectId: Long, revealPhotoId: Long?) {
        _stage.value = AddToProjectStage.Choosing
        onNavigate(Screen.GalleryProject(projectId = projectId, revealPhotoId = revealPhotoId))
    }

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
            @Assisted("uris") uris: List<Uri>,
        ): AddToProjectComponent
    }
}
