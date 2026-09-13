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

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ProjectDao
import com.RAZStudio.StudioRoom.core.database.model.GridFilter
import com.RAZStudio.StudioRoom.core.database.model.GridQueryBuilder
import com.RAZStudio.StudioRoom.core.database.model.GridSort
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.resources.R as CoreR
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.gallery_workspace.relink.RelinkCandidate
import com.RAZStudio.StudioRoom.feature.gallery_workspace.relink.RelinkCoordinator
import com.RAZStudio.StudioRoom.feature.gallery_workspace.relink.RelinkReport
import com.RAZStudio.StudioRoom.feature.gallery_workspace.thumbnail.ThumbnailWorker
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persisted grid view state for one project (Requirement 7.4).
 *
 * The defaults here have to be the ones a persisted `gridSortMode` of 0 decodes
 * to, because that is what a freshly created project stores. That is why the
 * packed direction bit means ASCENDING rather than descending: newest-first is
 * the sensible default, so it must be the value that needs no bit set.
 */
data class GridViewState(
    val sort: GridSort = GridSort.CapturedDate,
    val descending: Boolean = true,
    val filter: GridFilter = GridFilter.NONE,
    val columns: Int = 3,
    /**
     * Free-text search over filename, camera, lens and keywords (schema v3).
     * Deliberately NOT persisted in `projects.gridSortMode` like sort/filter:
     * a search that survived a restart would look like a project that had lost
     * photos.
     */
    val search: String = "",
    /** Showing the trash instead of the live grid (schema v4). */
    val trashed: Boolean = false,
    /** Collapsing RAW+JPEG pairs onto one tile. */
    val stacked: Boolean = false,
) {
    /** Packs into the single `projects.gridSortMode` column the schema already has. */
    val packedSortMode: Int
        get() = sort.ordinal or (if (descending) 0 else BIT_ASCENDING)

    companion object {
        private const val BIT_ASCENDING = 1 shl 4

        fun unpackSort(mode: Int): Pair<GridSort, Boolean> =
            GridSort.entries.getOrElse(mode and 0xF) { GridSort.CapturedDate } to
                (mode and BIT_ASCENDING == 0)
    }
}

/**
 * One project's photo grid (Requirement 7).
 *
 * Sort and filter are SQL over indexed columns and the result is PAGED, so cost
 * is bounded by what is on screen rather than by project size. Nothing here ever
 * materialises a whole project.
 */
class GalleryProjectComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @Assisted("projectId") val projectId: Long,
    @Assisted("revealPhotoId") val revealPhotoId: Long?,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val projectDao: ProjectDao,
    private val photoDao: PhotoDao,
    private val thumbnailDao: com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao,
    private val thumbnails: ThumbnailWorker,
    private val batchOps: com.RAZStudio.StudioRoom.feature.gallery_workspace
        .batch.BatchOperationsRepository,
    private val sidecarOps: com.RAZStudio.StudioRoom.feature.gallery_workspace
        .sidecar.SidecarOpsRepository,
    private val photoExporter: com.RAZStudio.StudioRoom.feature.gallery_workspace
        .export.ProjectPhotoExporter,
    private val relinkCoordinator: RelinkCoordinator,
    private val folderGrants: com.RAZStudio.StudioRoom.feature.gallery_workspace
        .sidecar.FolderGrantManager,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    /**
     * Null = the library-wide "All photos" scope ([ALL_PHOTOS_ID]); otherwise
     * this project. Every grid query takes the nullable form, so All photos is
     * the same screen with one predicate dropped rather than a parallel one.
     */
    private val scopeId: Long? = projectId.takeIf { it != ALL_PHOTOS_ID }

    /** True while showing every photo in the library rather than one project. */
    val isAllPhotos: Boolean get() = scopeId == null

    val projectName: StateFlow<String> =
        if (scopeId == null) MutableStateFlow("").asStateFlow()
        else projectDao.getById(projectId)
            .map { it?.name.orEmpty() }
            .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _view = MutableStateFlow(GridViewState())
    val view: StateFlow<GridViewState> = _view.asStateFlow()

    /**
     * What the QUERIES read, as opposed to what the text field shows.
     *
     * `LIKE '%term%'` cannot use an index, so every keystroke is a full scan of
     * the scope — fine at a few hundred photos, not at several thousand. Search
     * text is therefore debounced while sort/filter/column changes stay
     * immediate (those are index-backed and the user expects an instant snap).
     * Clearing the box is also immediate: waiting to show MORE photos reads as
     * lag, waiting to show fewer reads as typing.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val queryView: Flow<GridViewState> = kotlinx.coroutines.flow.combine(
        _view.map { it.copy(search = "") }.distinctUntilChanged(),
        _view.map { it.search }.distinctUntilChanged()
            .debounce { term -> if (term.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
    ) { structure, search -> structure.copy(search = search) }
        .distinctUntilChanged()

    /** Paged tiles for the current sort + filter. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photos: Flow<PagingData<PhotoGridRow>> = queryView
        .flatMapLatest { state ->
            Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    // Keep the window small: a large prefetch would defeat the
                    // point of paging on a project of thousands.
                    prefetchDistance = PAGE_SIZE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    photoDao.getGridPaged(
                        GridQueryBuilder.grid(
                            projectId = scopeId,
                            sort = state.sort,
                            descending = state.descending,
                            filter = state.filter,
                            search = state.search,
                            trashed = state.trashed,
                            stacked = state.stacked && !state.trashed,
                        )
                    )
                },
            ).flow
        }
        .cachedIn(componentScope)

    /**
     * Count under the active filter, so an empty grid can say WHY it is empty
     * (Requirement 7.7) instead of looking like an empty project.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredCount: StateFlow<Int> = queryView
        .flatMapLatest { state ->
            photoDao.observeGridCount(
                GridQueryBuilder.count(scopeId, state.filter, state.search, state.trashed)
            )
        }
        .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Total ignoring filters, to distinguish "filtered to nothing" from "empty". */
    val totalCount: StateFlow<Int> =
        (if (scopeId == null) photoDao.getPhotoCountAll() else photoDao.getPhotoCount(projectId))
            .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _reveal = MutableStateFlow(revealPhotoId)
    val reveal: StateFlow<Long?> = _reveal.asStateFlow()

    /** Live count of unreachable photos, so the UI offers directory relink only when useful. */
    val unreachableCount: StateFlow<Int> =
        (if (scopeId == null) photoDao.observeUnreachableCountAll()
         else photoDao.observeUnreachableCount(projectId))
            .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // Restore this project's persisted grid state (Requirement 7.4).
        componentScope.launch {
            val entity = projectDao.getById(projectId).first() ?: return@launch
            val (sort, descending) = GridViewState.unpackSort(entity.gridSortMode)
            _view.value = GridViewState(
                sort = sort,
                descending = descending,
                filter = GridFilter(entity.gridFilterMask),
                columns = entity.gridColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
            )
        }

        // Requirement 10.1 — verify readability without decoding, on every open.
        componentScope.launch { relinkCoordinator.verify(projectId) }

        thumbnails.startGridSession(projectId)
        lifecycle.doOnDestroy { thumbnails.cancelProject(projectId) }
    }

    fun consumeReveal() { _reveal.value = null }

    // ── Sort / filter / columns ──────────────────────────────────────────────

    fun setSort(sort: GridSort, descending: Boolean) =
        updateView { it.copy(sort = sort, descending = descending) }

    fun setFlagFilter(unflagged: Boolean, flagged: Boolean, rejected: Boolean) =
        updateView {
            it.copy(filter = it.filter.withFlagStates(unflagged, flagged, rejected))
        }

    fun setMinRatingFilter(stars: Int) =
        updateView { it.copy(filter = it.filter.withMinRating(stars)) }

    fun setColorFilter(label: Int) =
        updateView { it.copy(filter = it.filter.withColorLabel(label)) }

    fun clearFilter() = updateView { it.copy(filter = GridFilter.NONE, search = "") }

    // ── Search + metadata chips (schema v3) ─────────────────────────────────

    fun setSearch(query: String) = updateView { it.copy(search = query.take(80)) }

    // ── Trash + stacks (schema v4) ──────────────────────────────────────────

    /** Live count of trashed photos in scope, so the entry point can hide itself. */
    val trashCount: StateFlow<Int> = photoDao.observeTrashCount(scopeId)
        .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setTrashMode(on: Boolean) {
        clearSelection()
        updateView { it.copy(trashed = on) }
    }

    fun toggleStacks() = updateView { it.copy(stacked = !it.stacked) }

    /** Restore the selection (or [ids]) from the trash. */
    fun restoreFromTrash(ids: Collection<Long> = _selection.value) {
        if (ids.isEmpty()) return
        componentScope.launch {
            val n = batchOps.restoreFromTrash(ids)
            clearSelection()
            _batchMessage.value = BatchNotice(
                quantity(CoreR.plurals.gallery_trash_restored, n, n)
            )
        }
    }

    /** Delete the selection (or [ids]) for real — no undo past this point. */
    fun purgeFromTrash(ids: Collection<Long> = _selection.value) {
        if (ids.isEmpty()) return
        componentScope.launch {
            val n = batchOps.purge(ids)
            clearSelection()
            _batchMessage.value = BatchNotice(
                quantity(CoreR.plurals.gallery_trash_purged, n, n)
            )
        }
    }

    fun emptyTrash() {
        componentScope.launch {
            val ids = photoDao.getTrashed(scopeId).map { it.id }
            purgeFromTrash(ids)
        }
    }

    /**
     * Photos sharing a collapsed tile's stack, so tapping the badge can list the
     * pair. Returns an empty list when the row is not stacked.
     */
    suspend fun stackMembers(row: PhotoGridRow): List<Long> {
        if (row.stackCount <= 1) return emptyList()
        val photo = photoDao.getById(row.id) ?: return emptyList()
        if (photo.stackKey.isEmpty()) return emptyList()
        return photoDao.getStackMembers(photo.projectId, photo.stackKey).map { it.id }
    }

    /**
     * Purge trash older than the retention window, and fill `stackKey` on rows
     * imported before v4. Both are bounded, run once per open, and are silent —
     * a photo the user trashed a month ago should not announce itself.
     */
    private fun sweepTrashAndStacks() {
        componentScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() -
                com.RAZStudio.StudioRoom.core.database.entity.TRASH_RETENTION_DAYS *
                24L * 60L * 60L * 1000L
            val expired = runCatching { photoDao.getExpiredTrash(cutoff) }.getOrDefault(emptyList())
            if (expired.isNotEmpty()) {
                android.util.Log.i("GalleryProject", "trash sweep: purging ${expired.size} expired")
                runCatching { batchOps.purge(expired.map { it.id }) }
            }
            var filled = 0
            while (true) {
                val batch = runCatching { photoDao.getMissingStackKey(STACK_BACKFILL_BATCH) }
                    .getOrDefault(emptyList())
                if (batch.isEmpty()) break
                for (photo in batch) {
                    val key = com.RAZStudio.StudioRoom.core.database.model.StackKeys
                        .of(photo.displayName)
                    // A blank name would re-select this row forever; park it on
                    // its own id so the pass always terminates.
                    runCatching { photoDao.setStackKey(photo.id, key.ifEmpty { "id:" + photo.id }) }
                    filled++
                }
                if (batch.size < STACK_BACKFILL_BATCH) break
            }
            if (filled > 0) android.util.Log.i("GalleryProject", "stack key backfill: $filled row(s)")
        }
    }

    private val _cameras = MutableStateFlow<List<String>>(emptyList())
    /** Camera bodies present in scope, most-used first — filter chips. */
    val cameras: StateFlow<List<String>> = _cameras.asStateFlow()

    private val _lenses = MutableStateFlow<List<String>>(emptyList())
    val lenses: StateFlow<List<String>> = _lenses.asStateFlow()

    private val _keywordCloud = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    /** Every keyword in scope with its photo count, most-used first. */
    val keywordCloud: StateFlow<List<Pair<String, Int>>> = _keywordCloud.asStateFlow()

    fun refreshMetadataFacets() {
        componentScope.launch {
            _cameras.value = runCatching { photoDao.distinctCameras(scopeId) }.getOrDefault(emptyList())
            _lenses.value = runCatching { photoDao.distinctLenses(scopeId) }.getOrDefault(emptyList())
            _keywordCloud.value = runCatching {
                photoDao.allKeywordStrings(scopeId)
                    .flatMap { com.RAZStudio.StudioRoom.core.database.model.Keywords.decode(it) }
                    .groupingBy { it }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key })
                    .map { it.key to it.value }
            }.getOrDefault(emptyList())
        }
    }

    // ── Keywords ────────────────────────────────────────────────────────────

    suspend fun keywordsOf(photoId: Long): List<String> =
        com.RAZStudio.StudioRoom.core.database.model.Keywords
            .decode(photoDao.getById(photoId)?.keywords)

    /**
     * Add and/or remove keywords across [ids] (one photo or a whole selection).
     * Merging per-photo rather than overwriting means a batch "+sunset" keeps
     * each frame's existing tags.
     */
    fun editKeywords(ids: Collection<Long>, add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return
        componentScope.launch {
            val kw = com.RAZStudio.StudioRoom.core.database.model.Keywords
            var changed = 0
            for (photo in photoDao.getByIds(ids.toList())) {
                val next = kw.minus(kw.plus(photo.keywords, add), remove)
                if (next != photo.keywords) {
                    photoDao.setKeywords(photo.id, next)
                    changed++
                }
            }
            refreshMetadataFacets()
            _batchMessage.value = BatchNotice(
                quantity(CoreR.plurals.gallery_keywords_updated, changed, changed)
            )
        }
    }

    // ── One-shot EXIF backfill for pre-v3 rows ──────────────────────────────

    /**
     * Fill camera/lens/exposure on rows imported before schema v3, in bounded
     * batches so a 5 000-photo library does not stall the grid. Runs on open;
     * each pass handles [EXIF_BACKFILL_BATCH] rows and re-arms itself until the
     * query comes back empty. A file that genuinely has no EXIF is written with
     * a sentinel ISO of -1 so it is not re-probed forever.
     */
    private fun backfillExif() {
        componentScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val probe = com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoMetadataProbe
            var total = 0
            while (true) {
                val batch = runCatching { photoDao.getMissingExif(EXIF_BACKFILL_BATCH) }
                    .getOrDefault(emptyList())
                if (batch.isEmpty()) break
                for (photo in batch) {
                    val uri = runCatching { android.net.Uri.parse(photo.sourceUri) }.getOrNull() ?: continue
                    val meta = runCatching { probe.probe(appContext.contentResolver, uri) }.getOrNull()
                    runCatching {
                        photoDao.updateExif(
                            photoId = photo.id,
                            make = meta?.cameraMake.orEmpty(),
                            model = meta?.cameraModel.orEmpty(),
                            lens = meta?.lensModel.orEmpty(),
                            // -1 marks "probed, nothing there" so getMissingExif
                            // (iso = 0) stops returning this row.
                            iso = meta?.iso?.takeIf { it > 0 } ?: -1,
                            aperture = meta?.apertureF ?: 0f,
                            shutter = meta?.shutterSpeed ?: 0f,
                            focal = meta?.focalLengthMm ?: 0f,
                            lat = meta?.gpsLat, lon = meta?.gpsLon,
                            capturedAt = meta?.capturedAt,
                            widthPx = meta?.widthPx ?: 0,
                            heightPx = meta?.heightPx ?: 0,
                        )
                    }
                    total++
                }
                if (batch.size < EXIF_BACKFILL_BATCH) break
            }
            if (total > 0) {
                android.util.Log.i("GalleryProject", "EXIF backfill: filled $total row(s)")
                refreshMetadataFacets()
            }
        }
    }

    fun setColumns(columns: Int) =
        updateView { it.copy(columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)) }

    private fun updateView(transform: (GridViewState) -> GridViewState) {
        val next = transform(_view.value)
        _view.value = next
        componentScope.launch {
            val entity = projectDao.getById(projectId).first() ?: return@launch
            projectDao.update(
                entity.copy(
                    gridSortMode = next.packedSortMode,
                    gridFilterMask = next.filter.mask,
                    gridColumns = next.columns,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ── Per-photo attributes (Requirement 7.8, 7.10) ─────────────────────────

    /** Toggle flagged. Flagging a rejected photo clears the rejection. */
    fun toggleFlag(photoId: Long) = mutatePhoto(photoId) { photo ->
        photo.copy(flagState = if (photo.flagState == FLAG_FLAGGED) FLAG_NONE else FLAG_FLAGGED)
    }

    /** Toggle rejected. Rejecting a flagged photo clears the flag. */
    fun toggleReject(photoId: Long) = mutatePhoto(photoId) { photo ->
        photo.copy(flagState = if (photo.flagState == FLAG_REJECTED) FLAG_NONE else FLAG_REJECTED)
    }

    fun setRating(photoId: Long, stars: Int) = mutatePhoto(photoId) { photo ->
        photo.copy(rating = stars.coerceIn(0, 5))
    }

    fun setColorLabel(photoId: Long, label: Int) = mutatePhoto(photoId) { photo ->
        photo.copy(colorLabel = label.coerceIn(0, 15))
    }

    private fun mutatePhoto(
        photoId: Long,
        transform: (com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity) ->
        com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity,
    ) {
        componentScope.launch {
            // Re-read rather than trusting the row the tile was drawn from: the
            // grid row is a paged snapshot, and writing a whole PhotoEntity back
            // from a stale one would clobber whatever else changed meanwhile.
            val photo = photoDao.getById(photoId) ?: return@launch
            photoDao.update(transform(photo))
        }
    }

    // ── Multi-select + batch operations (Requirement 8) ──────────────────────

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())

    /** Selected photo ids; non-empty = multi-select mode is active (Req 8.1). */
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /**
     * One-shot snackbar notice. [onUndo] non-null = the snackbar offers Undo
     * (move, remove); [onExpire] runs when the snackbar leaves WITHOUT undo —
     * removal uses it to delete the deferred files (Req 14.32).
     */
    data class BatchNotice(
        val message: String,
        val onUndo: (() -> Unit)? = null,
        val onExpire: (() -> Unit)? = null,
    )

    private val _batchMessage = MutableStateFlow<BatchNotice?>(null)
    val batchMessage: StateFlow<BatchNotice?> = _batchMessage.asStateFlow()

    /**
     * Compare-and-set consume: the snackbar effect for notice A runs its
     * cleanup when notice B replaces it — an unconditional null-out there
     * would wipe B before its own effect ever saw it.
     */
    fun consumeBatchMessage(notice: BatchNotice) {
        _batchMessage.compareAndSet(notice, null)
    }

    fun toggleSelection(photoId: Long) {
        _selection.value =
            if (photoId in _selection.value) _selection.value - photoId
            else _selection.value + photoId
    }

    fun clearSelection() { _selection.value = emptySet() }

    /**
     * Explicit set/unset, as opposed to [toggleSelection] — a drag that passes
     * back over a tile must not flip it a second time.
     */
    fun setSelected(photoId: Long, selected: Boolean) {
        _selection.value = if (selected) _selection.value + photoId else _selection.value - photoId
    }

    /** Add every id in [ids] to the selection (drag-select commit). */
    fun addToSelection(ids: Collection<Long>) {
        if (ids.isNotEmpty()) _selection.value = _selection.value + ids
    }

    /** Select every photo matching the ACTIVE filter (Req 8.7) — SQL, not the page. */
    fun selectAllMatchingFilter() {
        componentScope.launch {
            val state = _view.value
            _selection.value = photoDao.getGridIds(
                GridQueryBuilder.ids(projectId, state.sort, state.descending, state.filter)
            ).toSet()
        }
    }

    // Attribute batches (Req 8.2). Selection is KEPT so a rating can be
    // followed by a flag on the same set — clearing is the user's exit.
    fun batchSetFlag(state: Int) = batchAttr { batchOps.setFlagState(it, state) }
    fun batchSetRating(stars: Int) = batchAttr { batchOps.setRating(it, stars) }
    fun batchSetColorLabel(label: Int) = batchAttr { batchOps.setColorLabel(it, label) }

    private fun batchAttr(op: suspend (Set<Long>) -> Unit) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        componentScope.launch { op(ids) }
    }

    /** A move/copy destination with the context the picker shows (Req 14.25). */
    data class TransferTarget(
        val id: Long,
        val name: String,
        val photoCount: Int,
        val coverThumbPath: String?,
    )

    /** Projects this selection could move/copy to — everything but this one. */
    suspend fun transferTargets(): List<TransferTarget> =
        projectDao.getAll().first()
            .filter { it.id != projectId }
            .map { p ->
                val coverId = p.coverPhotoId ?: photoDao.getEarliestAddedId(p.id)
                TransferTarget(
                    id = p.id,
                    name = p.name,
                    photoCount = photoDao.getPhotoCount(p.id).first(),
                    coverThumbPath = coverId?.let { thumbnailDao.getByPhotoId(it)?.path },
                )
            }

    /**
     * Create a project from the transfer picker (Req 14.26 — offered when no
     * destination exists yet). Mirrors the workspace screen's create.
     */
    suspend fun createTransferTarget(name: String): TransferTarget? {
        if (name.isBlank()) return null
        val now = System.currentTimeMillis()
        val minOrder = projectDao.getAll().first().minOfOrNull { it.sortOrder } ?: 0
        val id = runCatching {
            projectDao.insert(
                com.RAZStudio.StudioRoom.core.database.entity.ProjectEntity(
                    name = name.trim(),
                    createdAt = now,
                    updatedAt = now,
                    coverPhotoId = null,
                    sortOrder = minOrder - 1,
                    gridSortMode = 0,
                    gridFilterMask = 0,
                    gridColumns = 3,
                )
            )
        }.getOrNull() ?: return null
        java.io.File(appContext.filesDir, "gallery/projects/$id").mkdirs()
        return TransferTarget(id, name.trim(), 0, null)
    }

    fun batchMove(destProjectId: Long, destName: String) =
        movePhotos(_selection.value, destProjectId, destName)

    // ── Localized message helpers (Req 13.1) ─────────────────────────────────
    // Snackbar text is resolved here via appContext resources rather than being
    // hardcoded English (the previous state) or plumbed through a sealed type:
    // the notices are one-shot, so a locale change mid-snackbar is a non-issue,
    // and getString/getQuantityString respect the app locale.

    private val res get() = appContext.resources

    private fun quantity(id: Int, n: Int, vararg args: Any) =
        res.getQuantityString(id, n, *args)

    /** Joins localized fragments with the localized separator (Req 13.1/13.3). */
    private fun joinParts(vararg parts: String?) =
        parts.filterNotNull().joinToString(res.getString(CoreR.string.gallery_msg_separator))

    private fun transferResultMessage(
        headId: Int, r: com.RAZStudio.StudioRoom.feature.gallery_workspace
            .batch.BatchTransferResult, destName: String,
    ): String = joinParts(
        quantity(headId, r.transferred, r.transferred, destName),
        r.skippedDuplicates.takeIf { it > 0 }?.let {
            quantity(CoreR.plurals.gallery_batch_already_there, it, it)
        },
        r.failed.takeIf { it > 0 }?.let {
            quantity(CoreR.plurals.gallery_batch_failed_count, it, it)
        },
    )

    /** Move [ids] to [destProjectId], with snackbar Undo (Req 14.27). */
    fun movePhotos(ids: Set<Long>, destProjectId: Long, destName: String) {
        if (ids.isEmpty()) return
        componentScope.launch {
            val r = batchOps.moveToProject(ids, destProjectId)
            _selection.value = emptySet()
            _batchMessage.value = BatchNotice(
                message = transferResultMessage(CoreR.plurals.gallery_batch_moved, r, destName),
                // Undo = the same move in reverse; row identity is stable so the
                // original ids are still the right handles.
                onUndo = if (r.transferred > 0) {
                    { componentScope.launch { batchOps.moveToProject(ids, projectId) } }
                } else null,
            )
        }
    }

    fun batchCopy(destProjectId: Long, destName: String) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        componentScope.launch {
            val r = batchOps.copyToProject(ids, destProjectId)
            _selection.value = emptySet()
            _batchMessage.value = BatchNotice(
                transferResultMessage(CoreR.plurals.gallery_batch_copied, r, destName)
            )
        }
    }

    fun batchRemove() = removePhotos(_selection.value)

    /**
     * Remove [ids] with snackbar Undo (Req 14.32): rows go immediately, the
     * app-owned FILES only when the snackbar expires un-undone.
     */
    /**
     * Move photos to the trash (schema v4) rather than deleting them. The Undo
     * on the notice is now a convenience, not the only chance: anything missed
     * is still in the trash for [TRASH_RETENTION_DAYS].
     */
    fun removePhotos(ids: Set<Long>) {
        if (ids.isEmpty()) return
        componentScope.launch {
            val n = batchOps.moveToTrash(ids)
            clearSelection()
            _batchMessage.value = BatchNotice(
                message = quantity(CoreR.plurals.gallery_trash_moved, n, n),
                onUndo = { componentScope.launch { batchOps.restoreFromTrash(ids) } },
            )
        }
    }

    // ── Context-menu sidecar operations (task 8a) ─────────────────────────────

    /** Copy a photo's edit settings to the session clipboard (Req 14.12). */
    fun copyEditSettings(photoId: Long) {
        componentScope.launch {
            val ok = sidecarOps.copyToClipboard(photoId)
            if (!ok) _batchMessage.value =
                BatchNotice(res.getString(CoreR.string.gallery_no_edits_to_copy))
        }
    }

    fun clearEditClipboard() {
        com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.EditClipboard.clear()
    }

    /**
     * Paste the clipboard onto [targetIds] with [cats] (Req 14.14–14.19,
     * 14.18a/b). The clipboard's own source photo is excluded when present in
     * the selection. Reports applied count and notes when masks were left
     * behind (the one category never offered).
     */
    fun pasteEditSettings(
        targetIds: Set<Long>,
        cats: com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.PasteCategories,
    ) {
        val entry = com.RAZStudio.StudioRoom.feature.gallery_workspace
            .sidecar.EditClipboard.entry ?: return
        val targets = targetIds - entry.photoId
        if (targets.isEmpty()) return
        componentScope.launch {
            var applied = 0
            for (id in targets) if (sidecarOps.paste(id, entry, cats)) applied++
            _selection.value = emptySet()
            val skippedMasks = entry.snapshot.actionStack.any {
                it.maskPath != null || it.maskClass != null || it.maskClasses.isNotEmpty()
            }
            _batchMessage.value = BatchNotice(
                joinParts(
                    quantity(CoreR.plurals.gallery_pasted_onto, applied, applied),
                    if (targets.size != targetIds.size) {
                        res.getString(CoreR.string.gallery_paste_source_skipped)
                    } else null,
                    if (skippedMasks) {
                        res.getString(CoreR.string.gallery_paste_masks_never)
                    } else null,
                )
            )
        }
    }

    /** Target-kit probe for the paste sheet's cross-kit warning (Req 14.15). */
    suspend fun kitOf(photoId: Long): com.RAZStudio.StudioRoom.feature.gallery_workspace
        .sidecar.KitInfo? =
        photoDao.getById(photoId)?.let { sidecarOps.probeKit(it) }

    // ── Export photos (bake sidecars to finished files, 2026-09-07) ──────────

    val photoExportState get() = photoExporter.state

    fun exportPhotos(
        format: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawExportFormat,
        watermarkPresetName: String?,
    ) {
        if (photoExporter.isRunning) {
            _batchMessage.value = BatchNotice(res.getString(CoreR.string.gallery_export_photos_busy))
            return
        }
        photoExporter.start(projectId, format, watermarkPresetName) {
            _batchMessage.value = BatchNotice(res.getString(CoreR.string.gallery_export_photos_empty))
        }
    }

    fun cancelPhotoExport() = photoExporter.cancel()
    fun dismissPhotoExport() = photoExporter.dismiss()

    // ── Camera + lens profile (grid picker, 2026-09-07) ──────────────────────

    /** Current "camera · lens" of [photoId]'s sidecar, or null. */
    suspend fun lensProfileLabel(photoId: Long): String? = sidecarOps.lensProfileLabel(photoId)

    /** Persisted workspace of [photoId] (to prefill the picker), or null. */
    suspend fun workspaceOf(photoId: Long): com.RAZStudio.StudioRoom.feature.photo_editor
        .raw.model.WorkspaceConfig? = sidecarOps.loadSnapshot(photoId)?.workspace

    /**
     * Set (or clear, when [cameraId] and [lensId] are both blank) the camera+lens
     * profile on [photoId], preserving its edits; optionally on every photo of the
     * same EXIF kit too. [dbDir] is the materialised profile database directory.
     */
    fun applyLensProfile(
        photoId: Long,
        dbDir: String,
        cameraId: String,
        lensId: String,
        adapted: Boolean,
        focalOverrideMm: Float,
        applyToMatching: Boolean,
    ) {
        componentScope.launch {
            val clearing = cameraId.isBlank() && lensId.isBlank()
            val transform: (com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig) ->
                com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig = { ws ->
                ws.copy(
                    lensfunDbDir = if (clearing) "" else dbDir,
                    lensfunCameraId = cameraId.trim(),
                    lensfunLensId = lensId.trim(),
                    lensfunAdaptedMode = adapted && !clearing,
                    lensfunFocalOverrideMm = if (clearing) 0f else focalOverrideMm.coerceAtLeast(0f),
                    lensfunMatchConfidence = 0,
                )
            }
            val n = if (applyToMatching) sidecarOps.applyWorkspaceToMatchingKit(projectId, photoId, transform)
                    else (if (sidecarOps.updateWorkspace(photoId, transform)) 1 else 0)
            _batchMessage.value = BatchNotice(
                if (clearing) res.getString(CoreR.string.gallery_lens_cleared)
                else res.getString(CoreR.string.gallery_lens_applied, n)
            )
        }
    }

    /** Reset a photo's edits (Req 14.21–14.23) — history retained in the sidecar. */
    /**
     * Apply one camera+lens profile to every selected photo, edits preserved
     * (see [SidecarOpsRepository.updateWorkspace]). The single-photo picker's
     * "also apply to matching kit" is EXIF-driven; this is "apply to exactly
     * what I selected", which is what a mixed shoot needs.
     */
    fun applyLensProfileToSelection(
        dbDir: String,
        cameraId: String,
        lensId: String,
        adapted: Boolean,
        focalOverrideMm: Float,
    ) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        componentScope.launch {
            val clearing = cameraId.isBlank() && lensId.isBlank()
            var n = 0
            for (id in ids) {
                val ok = sidecarOps.updateWorkspace(id) { ws ->
                    ws.copy(
                        lensfunDbDir = if (clearing) "" else dbDir,
                        lensfunCameraId = cameraId.trim(),
                        lensfunLensId = lensId.trim(),
                        lensfunAdaptedMode = adapted && !clearing,
                        lensfunFocalOverrideMm = if (clearing) 0f else focalOverrideMm.coerceAtLeast(0f),
                        lensfunMatchConfidence = 0,
                    )
                }
                if (ok) n++
            }
            _batchMessage.value = BatchNotice(
                if (clearing) res.getString(CoreR.string.gallery_lens_cleared)
                else res.getString(CoreR.string.gallery_lens_applied, n)
            )
            clearSelection()
        }
    }

    // ── Versions (sidecar revision history) ─────────────────────────────────

    /** Saved revisions of [photoId], newest first. Empty when never edited. */
    suspend fun revisionsOf(photoId: Long): List<com.RAZStudio.StudioRoom.feature.photo_editor
        .raw.sidecar.SidecarRevision> = sidecarOps.revisionsOf(photoId)

    fun restoreRevision(photoId: Long, index: Int) {
        componentScope.launch {
            val ok = sidecarOps.restoreRevision(photoId, index)
            _batchMessage.value = BatchNotice(
                res.getString(
                    if (ok) CoreR.string.gallery_version_restored
                    else CoreR.string.gallery_version_restore_failed
                )
            )
        }
    }

    fun resetEdits(photoId: Long) {
        componentScope.launch {
            val ok = sidecarOps.reset(photoId)
            _batchMessage.value = BatchNotice(
                res.getString(
                    if (ok) CoreR.string.gallery_edits_reset
                    else CoreR.string.gallery_no_edits_to_reset
                )
            )
        }
    }

    // ── Thumbnails ───────────────────────────────────────────────────────────

    /**
     * Report the scroll window (Requirement 6.3/6.4). Takes the ids currently
     * loaded in display order, so the worker never sees the whole project.
     */
    fun onVisibleRangeChanged(
        orderedIds: List<Long>,
        firstIndex: Int,
        lastIndex: Int,
    ) {
        if (orderedIds.isEmpty()) return
        thumbnails.setWindow(
            projectId = projectId,
            orderedIds = orderedIds,
            firstVisibleIndex = firstIndex,
            lastVisibleIndex = lastIndex,
            lookahead = LOOKAHEAD,
        )
        val lo = firstIndex.coerceIn(0, orderedIds.lastIndex)
        val hi = lastIndex.coerceIn(lo, orderedIds.lastIndex)
        componentScope.launch {
            orderedIds.subList(lo, hi + 1).forEach { thumbnails.touch(it) }
        }
    }

    // ── Relink (Requirement 10) ───────────────────────────────────────────────

    /** Repoint one unreachable photo to a freshly picked document (Req 10.6). */
    fun relinkOne(photoId: Long, newUri: android.net.Uri) {
        componentScope.launch {
            val ok = relinkCoordinator.relinkOne(photoId, newUri)
            _batchMessage.value = BatchNotice(
                res.getString(
                    if (ok) CoreR.string.gallery_relinked
                    else CoreR.string.gallery_relink_unreadable
                )
            )
        }
    }

    /** Intent for the directory-relink picker (Req 10.7). */
    fun relinkDirectoryIntent(): android.content.Intent = folderGrants.buildTreeRequestIntent()

    /**
     * Persist the tree grant and scan it against every unreachable photo
     * (Req 10.7). The result is surfaced via [relinkReport] so the UI can
     * apply-or-confirm the lower-confidence tiers.
     */
    fun onRelinkDirectoryPicked(treeUri: android.net.Uri) {
        componentScope.launch {
            if (!folderGrants.persistTreeGrant(treeUri)) {
                _batchMessage.value =
                    BatchNotice(res.getString(CoreR.string.gallery_relink_folder_denied))
                return@launch
            }
            _relinkReport.value = relinkCoordinator.relinkByDirectory(projectId, treeUri)
        }
    }

    private val _relinkReport = MutableStateFlow<RelinkReport?>(null)

    /** One-shot directory-relink outcome (Req 10.7d), consumed by [consumeRelinkReport]. */
    val relinkReport: StateFlow<RelinkReport?> = _relinkReport.asStateFlow()

    /**
     * Dismiss the report. When [confirmedPending] is non-empty, those
     * NameAndSize/NameOnly candidates the user chose to accept are written now
     * — they were deliberately NOT applied during the scan itself (Req 10.7a).
     */
    fun consumeRelinkReport(confirmedPending: List<RelinkCandidate> = emptyList()) {
        _relinkReport.value = null
        if (confirmedPending.isEmpty()) return
        componentScope.launch {
            val n = relinkCoordinator.confirmPending(confirmedPending)
            _batchMessage.value =
                BatchNotice(quantity(CoreR.plurals.gallery_relink_linked_more, n, n))
        }
    }
    init {
        backfillExif()
        refreshMetadataFacets()
        sweepTrashAndStacks()
    }


    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
            @Assisted("projectId") projectId: Long,
            @Assisted("revealPhotoId") revealPhotoId: Long?,
        ): GalleryProjectComponent
    }

    companion object {
        /**
         * Sentinel projectId for the library-wide "All photos" grid. Negative so
         * it can never collide with a Room autoGenerate row id.
         */
        const val ALL_PHOTOS_ID = -1L

        /** Typing settles for this long before the (unindexable) LIKE scan runs. */
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** Rows re-probed per EXIF backfill pass — bounded so the grid stays live. */
        private const val EXIF_BACKFILL_BATCH = 200

        /** Rows given a stack key per pass. Pure string work, so a larger batch. */
        private const val STACK_BACKFILL_BATCH = 500

        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 6
        const val FLAG_NONE = 0
        const val FLAG_FLAGGED = 1
        const val FLAG_REJECTED = 2

        private const val PAGE_SIZE = 60
        private const val LOOKAHEAD = 12
    }
}
