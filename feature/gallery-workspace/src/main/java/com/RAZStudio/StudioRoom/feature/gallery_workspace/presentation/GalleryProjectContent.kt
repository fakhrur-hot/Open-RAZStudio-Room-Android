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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.RAZStudio.StudioRoom.core.resources.icons.Search
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.RAZStudio.StudioRoom.core.database.model.GridSort
import com.RAZStudio.StudioRoom.core.database.model.PhotoGridRow
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Block
import com.RAZStudio.StudioRoom.core.resources.icons.BrokenImageAlt
import com.RAZStudio.StudioRoom.core.resources.icons.CheckCircle
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.ContentCopy
import com.RAZStudio.StudioRoom.core.resources.icons.ContentPaste
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.FileExport
import com.RAZStudio.StudioRoom.core.resources.icons.FilterAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Flag
import com.RAZStudio.StudioRoom.core.resources.icons.Folder
import com.RAZStudio.StudioRoom.core.resources.icons.GridOn
import com.RAZStudio.StudioRoom.core.resources.icons.CameraAlt
import com.RAZStudio.StudioRoom.core.resources.icons.Restore
import com.RAZStudio.StudioRoom.core.resources.icons.Label
import com.RAZStudio.StudioRoom.core.resources.icons.Link
import com.RAZStudio.StudioRoom.core.resources.icons.MoreVert
import com.RAZStudio.StudioRoom.core.resources.icons.Segment
import com.RAZStudio.StudioRoom.core.resources.icons.SelectAll
import com.RAZStudio.StudioRoom.core.resources.icons.Star
import com.RAZStudio.StudioRoom.core.resources.icons.Storage
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoImporter
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryProjectComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GridViewState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * One project's photo grid (Requirement 7).
 *
 * The list is PAGED and sorted/filtered in SQL, so nothing here is proportional
 * to project size. Every indicator a tile draws comes from the same joined row —
 * no per-tile lookups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryProjectContent(component: GalleryProjectComponent) {
    val name by component.projectName.collectAsState()
    val viewState by component.view.collectAsState()
    val filteredCount by component.filteredCount.collectAsState()
    val totalCount by component.totalCount.collectAsState()
    val reveal by component.reveal.collectAsState()
    val selection by component.selection.collectAsState()
    val batchMessage by component.batchMessage.collectAsState()
    val items = component.photos.collectAsLazyPagingItems()

    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val androidView = LocalView.current
    val scope = rememberCoroutineScope()

    val selectionMode = selection.isNotEmpty()
    // Back exits multi-select before it exits the screen (platform convention).
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        component.clearSelection()
    }

    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showColumns by remember { mutableStateOf(false) }
    // Batch-bar popups: rating menu, label menu, move/copy project picker,
    // remove confirmation.
    var showBatchFlag by remember { mutableStateOf(false) }
    var showBatchRate by remember { mutableStateOf(false) }
    var showBatchLabel by remember { mutableStateOf(false) }
    var transferPicker by remember { mutableStateOf<TransferMode?>(null) }
    var transferTargets by remember {
        mutableStateOf<List<GalleryProjectComponent.TransferTarget>>(emptyList())
    }
    /** Photo ids the transfer picker acts on (selection or a single tile). */
    var transferIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    /** Photo ids a pending remove acts on. */
    var removeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    /** Row whose sidecarBesideOriginal drives the single-remove wording; null = batch. */
    var removeRow by remember { mutableStateOf<PhotoGridRow?>(null) }
    // Task 8a state: the tile whose ⋮ opened the context menu, the paste
    // targets awaiting category selection, and the single-reset confirmation.
    var contextMenuFor by remember { mutableStateOf<PhotoGridRow?>(null) }
    // Camera+lens profile picker (2026-09-07): which row it is open for, and the
    // current-profile subtitle for the open context menu (loaded from the sidecar).
    var lensSheetFor by remember { mutableStateOf<PhotoGridRow?>(null) }
    var showPhotoExport by remember { mutableStateOf(false) }
    // Loupe / info / keywords / versions (2026-09-07). The loupe drives off the
    // SAME paging list the grid renders, so it inherits sort, filter and search.
    var loupeIndex by remember { mutableStateOf<Int?>(null) }
    var infoRow by remember { mutableStateOf<PhotoGridRow?>(null) }
    var keywordTargets by remember { mutableStateOf<List<Long>>(emptyList()) }
    var versionsRow by remember { mutableStateOf<PhotoGridRow?>(null) }
    var compareIndex by remember { mutableStateOf<Int?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var lensSheetForSelection by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trashCount by component.trashCount.collectAsState()
    // Back leaves the trash before it leaves the screen — the trash is a mode,
    // not a destination, so exiting the project from it would lose the context.
    androidx.activity.compose.BackHandler(enabled = viewState.trashed) {
        component.setTrashMode(false)
    }
    val cameras by component.cameras.collectAsState()
    val lenses by component.lenses.collectAsState()
    val keywordCloud by component.keywordCloud.collectAsState()
    val photoExportState by component.photoExportState.collectAsState()
    var menuLensLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contextMenuFor?.id) {
        menuLensLabel = null
        contextMenuFor?.let { menuLensLabel = component.lensProfileLabel(it.id) }
    }
    var pasteTargets by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pasteTargetKit by remember {
        mutableStateOf<com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.KitInfo?>(null)
    }
    var resetRow by remember { mutableStateOf<PhotoGridRow?>(null) }

    // Task 10: relink state. Single-photo relink picks one replacement
    // document; directory relink scans a whole folder against every
    // unreachable photo in the project.
    val unreachableCount by component.unreachableCount.collectAsState()
    val relinkReport by component.relinkReport.collectAsState()
    var relinkTargetPhotoId by remember { mutableStateOf<Long?>(null) }
    val singleRelinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val id = relinkTargetPhotoId
        relinkTargetPhotoId = null
        if (uri != null && id != null) component.relinkOne(id, uri)
    }
    val relinkTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { component.onRelinkDirectoryPicked(it) }
    }

    val undoLabel = stringResource(R.string.gallery_undo)
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(batchMessage) {
        val notice = batchMessage ?: return@LaunchedEffect
        var undone = false
        try {
            val result = snackbarHostState.showSnackbar(
                message = notice.message,
                actionLabel = if (notice.onUndo != null) undoLabel else null,
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                undone = true
                notice.onUndo?.invoke()
            }
        } finally {
            // Runs on dismissal AND when a newer notice cancels this one — the
            // deferred file deletion (remove) must commit either way unless the
            // user actually pressed Undo. Consume is compare-and-set so this
            // cleanup can never wipe a NEWER notice that replaced us.
            if (!undone) notice.onExpire?.invoke()
            component.consumeBatchMessage(notice)
        }
    }

    // Requirement 16.5b — respect the system reduced-motion setting. An animated
    // highlight is the clearest confirmation for most users and exactly the wrong
    // thing for someone who asked the system to stop animating.
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val highlight = remember { Animatable(0f) }

    // Requirement 6.3/6.4 — drive the thumbnail worker from the scroll window.
    // The ids come from the loaded page window, so the queue is bounded by what
    // paging is holding rather than by the project.
    LaunchedEffect(gridState, items.itemCount) {
        snapshotFlow {
            val info = gridState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) null else info.first().index to info.last().index
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (first, last) ->
                component.onVisibleRangeChanged(
                    orderedIds = items.itemSnapshotList.items.map { it.id },
                    firstIndex = first,
                    lastIndex = last,
                )
            }
    }

    LaunchedEffect(reveal, items.itemCount) {
        val target = reveal ?: return@LaunchedEffect
        val loaded = items.itemSnapshotList.items
        val index = loaded.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect

        gridState.scrollToItem(index)

        // Requirement 16.5c — a visual highlight conveys nothing to a screen
        // reader, so announce the reveal too.
        androidView.announceForAccessibility(loaded[index].displayName)

        if (reduceMotion) {
            highlight.snapTo(1f)
            kotlinx.coroutines.delay(HIGHLIGHT_MS)
            highlight.snapTo(0f)
        } else {
            highlight.snapTo(1f)
            highlight.animateTo(0f, animationSpec = tween(HIGHLIGHT_MS.toInt()))
        }
        component.consumeReveal()
    }

    Scaffold(
        // Req 13.8 — every BatchNotice (move/copy/remove/paste/reset/relink
        // outcomes, incl. the Undo offer) lands here; the polite live region
        // makes TalkBack announce it even when focus is elsewhere.
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(
                snackbarHostState,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        bottomBar = {
            if (selectionMode) {
                if (viewState.trashed) TrashActionBar(
                    onRestore = { component.restoreFromTrash() },
                    onDeleteForever = { confirmPurge = selection },
                ) else BatchActionBar(
                    onFlag = { showBatchFlag = true },
                    onRate = { showBatchRate = true },
                    onLabel = { showBatchLabel = true },
                    onKeywords = { keywordTargets = selection.toList() },
                    onLensProfile = { lensSheetForSelection = true },
                    onMove = {
                        scope.launch {
                            transferIds = selection
                            transferTargets = component.transferTargets()
                            transferPicker = TransferMode.Move
                        }
                    },
                    onCopy = {
                        scope.launch {
                            transferIds = selection
                            transferTargets = component.transferTargets()
                            transferPicker = TransferMode.Copy
                        }
                    },
                    // Req 8.8 / 14.18 — paste across the whole selection. Shown
                    // only while the clipboard holds something.
                    onPaste = if (!com.RAZStudio.StudioRoom.feature.gallery_workspace
                            .sidecar.EditClipboard.isEmpty
                    ) {
                        {
                            pasteTargetKit = null
                            pasteTargets = selection
                        }
                    } else null,
                    onRemove = {
                        removeIds = selection
                        removeRow = null
                        showRemoveConfirm = true
                    },
                )
            } else {
                ProjectPhotoExportCard(
                    state = photoExportState,
                    onCancel = component::cancelPhotoExport,
                    onDismiss = component::dismissPhotoExport,
                )
            }
        },
        topBar = {
            if (selectionMode) {
                // Req 8.1 — live selection count; Req 8.7 — select-all-matching-filter.
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { component.clearSelection() }) {
                            Icon(
                                Icons.Rounded.Close,
                                stringResource(R.string.gallery_exit_selection),
                            )
                        }
                    },
                    title = {
                        Text(stringResource(R.string.gallery_selected_count, selection.size))
                    },
                    actions = {
                        IconButton(onClick = { component.selectAllMatchingFilter() }) {
                            Icon(
                                Icons.Outlined.SelectAll,
                                stringResource(R.string.gallery_select_all_filtered),
                            )
                        }
                    },
                )
            } else TopAppBar(
                navigationIcon = {
                    IconButton(onClick = component.onGoBack) {
                        Icon(Icons.Rounded.ArrowBack, stringResource(R.string.exit))
                    }
                },
                title = {
                    if (searchOpen) {
                        SearchField(
                            value = viewState.search,
                            onValueChange = component::setSearch,
                            onClose = { component.setSearch(""); searchOpen = false },
                        )
                    } else Text(
                        text = when {
                            viewState.trashed -> stringResource(R.string.gallery_trash)
                            component.isAllPhotos -> stringResource(R.string.gallery_all_photos)
                            else -> name.ifBlank { stringResource(R.string.gallery_project_title) }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // Requirement 10.7 — directory relink, offered only when
                    // there is something to relink.
                    if (unreachableCount > 0) {
                        IconButton(onClick = {
                            relinkTreeLauncher.launch(component.relinkDirectoryIntent())
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = stringResource(
                                    R.string.gallery_relink_directory_action, unreachableCount,
                                ),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (viewState.trashed) {
                        // In the trash the only global action that makes sense is
                        // emptying it; sort/filter/export are hidden below.
                        TextButton(onClick = { confirmPurge = ALL_TRASH }) {
                            Text(
                                stringResource(R.string.gallery_trash_empty_action),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.gallery_search),
                            tint = if (viewState.search.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                   else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { showPhotoExport = true }) {
                        Icon(Icons.Outlined.FileExport, stringResource(R.string.gallery_export_photos))
                    }
                    IconButton(onClick = { showSort = true }) {
                        Icon(Icons.Rounded.Segment, stringResource(R.string.gallery_sort))
                    }
                    IconButton(onClick = { showFilter = true }) {
                        Icon(
                            imageVector = Icons.Rounded.FilterAlt,
                            contentDescription = stringResource(R.string.gallery_filter),
                            // Requirement 7.3 — an active filter must be visible
                            // in the bar, or a partial grid reads as data loss.
                            tint = if (viewState.filter.isEmpty) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                stringResource(R.string.gallery_menu_more),
                            )
                        }
                        DropdownMenu(showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.gallery_stacks)) },
                                leadingIcon = {
                                    if (viewState.stacked) Icon(Icons.Rounded.CheckCircle, null)
                                },
                                onClick = { component.toggleStacks(); showOverflow = false },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (trashCount > 0)
                                            stringResource(R.string.gallery_trash) + "  ($trashCount)"
                                        else stringResource(R.string.gallery_trash)
                                    )
                                },
                                leadingIcon = {
                                    if (viewState.trashed) Icon(Icons.Rounded.CheckCircle, null)
                                },
                                onClick = {
                                    component.setTrashMode(!viewState.trashed)
                                    showOverflow = false
                                },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showColumns = true }) {
                            Icon(
                                Icons.Outlined.GridOn,
                                stringResource(R.string.gallery_columns),
                            )
                        }
                        DropdownMenu(showColumns, onDismissRequest = { showColumns = false }) {
                            for (n in GalleryProjectComponent.MIN_COLUMNS..
                                GalleryProjectComponent.MAX_COLUMNS) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gallery_columns_n, n)) },
                                    onClick = {
                                        component.setColumns(n)
                                        showColumns = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        when {
            // Requirement 7.6 — "filtered to nothing" and "empty project" look
            // identical on screen but mean completely different things, so name
            // the filter and offer the way out rather than showing bare emptiness.
            totalCount > 0 && filteredCount == 0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.gallery_filter_empty, totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.gallery_filter_active,
                        describeFilter(viewState),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = component::clearFilter) {
                    Text(stringResource(R.string.gallery_filter_clear))
                }
            }

            totalCount == 0 -> Box(
                modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.gallery_project_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Box(Modifier.fillMaxSize().padding(inner)) {
                // Facet chips: the metadata actually present in this scope. They
                // WRITE THE SEARCH BOX rather than adding a parallel filter —
                // one predicate to reason about, and the user can see and edit
                // what the chip did.
                if (searchOpen) {
                    SearchFacetChips(
                        cameras = cameras,
                        lenses = lenses,
                        keywords = keywordCloud,
                        active = viewState.search,
                        onPick = component::setSearch,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                // Drag-to-select (owner request): long-press starts a selection
                // drag; every tile the finger crosses is ADDED (never toggled, so
                // dragging back over one does not clear it). Hit-testing goes
                // through the grid's own layout info, which is the only source
                // that stays correct while the list scrolls and repopulates.
                val dragSelect = if (!selectionMode) Modifier else
                    Modifier.pointerInput(items.itemCount, viewState.columns) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            hitTestPhotoId(gridState, offset, items)?.let { id ->
                                component.setSelected(id, true)
                            }
                        },
                        onDrag = { change, _ ->
                            hitTestPhotoId(gridState, change.position, items)?.let { id ->
                                component.setSelected(id, true)
                            }
                        },
                    )
                    }
                LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(viewState.columns),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (searchOpen) FACET_ROW_HEIGHT else 0.dp)
                    .then(dragSelect),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.id },
                ) { index ->
                    val row = items[index] ?: return@items
                    PhotoGridTile(
                        row = row,
                        highlightAlpha = if (row.id == reveal) highlight.value else 0f,
                        selectionMode = selectionMode,
                        selected = row.id in selection,
                        reduceMotion = reduceMotion,
                        onToggleSelect = { component.toggleSelection(row.id) },
                        onOverflow = { contextMenuFor = row },
                        // Tap = EDIT (owner decision 2026-09-07: "what is the
                        // point I clicked?" — a tap that lands on a rating screen
                        // is a dead end). The full-screen viewer stays reachable
                        // from ⋮ → View for culling runs.
                        onOpen = {
                            component.onNavigate(
                                com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RawEditor(
                                    uri = android.net.Uri.parse(row.sourceUri),
                                    projectContext = com.RAZStudio.StudioRoom.core.ui.utils
                                        .navigation.ProjectPhotoRef(
                                            projectId = component.projectId,
                                            photoId = row.id,
                                            displayName = row.displayName,
                                        ),
                                )
                            )
                        },
                        onFlag = { component.toggleFlag(row.id) },
                        onReject = { component.toggleReject(row.id) },
                        onRate = { component.setRating(row.id, it) },
                    )
                }
                }
                // Floating day pill: the capture date of whatever is at the top
                // of the viewport. A true sticky header per day cannot be built
                // over PagingData without walking every loaded row on each
                // recomposition; this gives the same "where am I in the shoot"
                // read for O(1) work, and only when sorted by date.
                if (viewState.sort == GridSort.CapturedDate || viewState.sort == GridSort.AddedDate) {
                    val topLabel by remember(items.itemCount) {
                        derivedStateOf {
                            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                            val row = first?.let { items.peek(it) }
                            formatDay(
                                if (viewState.sort == GridSort.AddedDate) row?.addedAt
                                else row?.capturedAt ?: row?.addedAt
                            )
                        }
                    }
                    if (topLabel.isNotBlank()) {
                        androidx.compose.material3.Surface(
                            tonalElevation = 3.dp,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                        ) {
                            Text(
                                topLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSort) {
        SortSheet(
            state = viewState,
            onPick = component::setSort,
            onDismiss = { showSort = false },
        )
    }
    if (showFilter) {
        FilterSheet(
            state = viewState,
            component = component,
            onDismiss = { showFilter = false },
        )
    }

    // ── Batch popups (Req 8.2/8.3/8.6) ───────────────────────────────────────

    if (showBatchFlag) {
        AlertDialog(
            onDismissRequest = { showBatchFlag = false },
            title = { Text(stringResource(R.string.gallery_batch_flag)) },
            text = {
                Row {
                    FilterChip(
                        selected = false,
                        onClick = {
                            component.batchSetFlag(GalleryProjectComponent.FLAG_FLAGGED)
                            showBatchFlag = false
                        },
                        label = { Text(stringResource(R.string.gallery_flag_flagged)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = false,
                        onClick = {
                            component.batchSetFlag(GalleryProjectComponent.FLAG_REJECTED)
                            showBatchFlag = false
                        },
                        label = { Text(stringResource(R.string.gallery_flag_rejected)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = false,
                        onClick = {
                            component.batchSetFlag(GalleryProjectComponent.FLAG_NONE)
                            showBatchFlag = false
                        },
                        label = { Text(stringResource(R.string.gallery_flag_none)) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchFlag = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showBatchRate) {
        AlertDialog(
            onDismissRequest = { showBatchRate = false },
            title = { Text(stringResource(R.string.gallery_batch_rate)) },
            text = {
                Row {
                    for (stars in 0..5) {
                        RatingChip(
                            stars = stars,
                            selected = false,
                            onClick = {
                                component.batchSetRating(stars)
                                showBatchRate = false
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchRate = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showBatchLabel) {
        AlertDialog(
            onDismissRequest = { showBatchLabel = false },
            title = { Text(stringResource(R.string.gallery_batch_label)) },
            text = {
                Row {
                    FilterChip(
                        selected = false,
                        onClick = { component.batchSetColorLabel(0); showBatchLabel = false },
                        label = { Text(stringResource(R.string.gallery_action_color_clear)) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                    for (label in 1..COLOR_LABELS.size) {
                        Spacer(Modifier.width(4.dp))
                        ColorLabelChip(
                            label = label,
                            selected = false,
                            onClick = {
                                component.batchSetColorLabel(label)
                                showBatchLabel = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchLabel = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    transferPicker?.let { mode ->
        fun run(target: GalleryProjectComponent.TransferTarget) {
            when (mode) {
                TransferMode.Move -> component.movePhotos(transferIds, target.id, target.name)
                TransferMode.Copy -> component.batchCopy(target.id, target.name)
            }
            transferPicker = null
        }
        TransferPickerDialog(
            title = stringResource(
                if (mode == TransferMode.Move) R.string.gallery_batch_move
                else R.string.gallery_batch_copy
            ),
            targets = transferTargets,
            onPick = ::run,
            onCreate = { name ->
                scope.launch {
                    component.createTransferTarget(name)?.let(::run)
                }
            },
            onDismiss = { transferPicker = null },
        )
    }

    if (showRemoveConfirm) {
        // Req 14.28–14.30 — the confirmation copy branches on where the edits
        // live: beside the photo they SURVIVE removal; in the app fallback they
        // are deleted with the entry.
        val single = removeRow
        val body = when {
            single != null && single.hasEdits == 1 && single.sidecarBesideOriginal == 1 ->
                stringResource(R.string.gallery_remove_confirm_beside, single.displayName)
            single != null && single.hasEdits == 1 ->
                stringResource(R.string.gallery_remove_confirm_fallback, single.displayName)
            single != null ->
                stringResource(R.string.gallery_remove_confirm_plain, single.displayName)
            else ->
                pluralStringResource(
                    R.plurals.gallery_batch_remove_confirm, removeIds.size, removeIds.size,
                )
        }
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.gallery_batch_remove)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    component.removePhotos(removeIds)
                    showRemoveConfirm = false
                }) { Text(stringResource(R.string.gallery_batch_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // ── Task 10: directory-relink report (Req 10.7d) ─────────────────────────
    // Fingerprint matches are already applied by the time this shows; this is
    // only for the tiers that need a look before they're written (10.7a).
    relinkReport?.let { report ->
        AlertDialog(
            onDismissRequest = { component.consumeRelinkReport() },
            title = { Text(stringResource(R.string.gallery_relink_report_title)) },
            text = {
                Column {
                    if (report.applied.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.gallery_relink_report_applied, report.applied.size,
                            )
                        )
                    }
                    if (report.pendingConfirmation.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.gallery_relink_report_pending,
                                report.pendingConfirmation.size,
                            )
                        )
                    }
                    if (report.ambiguous.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.gallery_relink_report_ambiguous, report.ambiguous.size,
                            )
                        )
                    }
                    if (report.unmatched.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.gallery_relink_report_unmatched, report.unmatched.size,
                            )
                        )
                    }
                    if (report.applied.isEmpty() && report.pendingConfirmation.isEmpty() &&
                        report.ambiguous.isEmpty() && report.unmatched.isEmpty()
                    ) {
                        Text(stringResource(R.string.gallery_relink_report_none))
                    }
                }
            },
            confirmButton = {
                if (report.pendingConfirmation.isNotEmpty()) {
                    TextButton(onClick = { component.consumeRelinkReport(report.pendingConfirmation) }) {
                        Text(stringResource(R.string.gallery_relink_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { component.consumeRelinkReport() }) {
                    Text(
                        stringResource(
                            if (report.pendingConfirmation.isNotEmpty()) android.R.string.cancel
                            else android.R.string.ok
                        )
                    )
                }
            },
        )
    }

    // ── Task 8a sheets ───────────────────────────────────────────────────────

    contextMenuFor?.let { row ->
        PhotoContextMenu(
            row = row,
            onDismiss = { contextMenuFor = null },
            onSelect = { component.toggleSelection(row.id) },
            onFlag = { component.toggleFlag(row.id) },
            onReject = { component.toggleReject(row.id) },
            onRate = { component.setRating(row.id, it) },
            onColorLabel = { component.setColorLabel(row.id, it) },
            onCopySettings = { component.copyEditSettings(row.id) },
            onPasteSettings = {
                contextMenuFor = null
                pasteTargetKit = null
                pasteTargets = setOf(row.id)
                scope.launch { pasteTargetKit = component.kitOf(row.id) }
            },
            onClearClipboard = { component.clearEditClipboard() },
            onResetEdits = {
                contextMenuFor = null
                resetRow = row
            },
            onMove = {
                contextMenuFor = null
                scope.launch {
                    transferIds = setOf(row.id)
                    transferTargets = component.transferTargets()
                    transferPicker = TransferMode.Move
                }
            },
            onRemove = {
                contextMenuFor = null
                removeIds = setOf(row.id)
                removeRow = row
                showRemoveConfirm = true
            },
            onRelink = {
                contextMenuFor = null
                relinkTargetPhotoId = row.id
                singleRelinkLauncher.launch(PhotoImporter.IMPORT_MIME_TYPES)
            },
            onLensProfile = {
                contextMenuFor = null
                lensSheetFor = row
            },
            lensProfileLabel = menuLensLabel,
            onView = {
                val r = contextMenuFor
                contextMenuFor = null
                r?.let { row ->
                    (0 until items.itemCount).firstOrNull { items.peek(it)?.id == row.id }
                        ?.let { loupeIndex = it }
                }
            },
            onShare = {
                val r = contextMenuFor
                contextMenuFor = null
                r?.let { shareSourcePhoto(context, it) }
            },
            onInfo = { val r = contextMenuFor; contextMenuFor = null; infoRow = r },
            onKeywords = { val r = contextMenuFor; contextMenuFor = null; keywordTargets = listOfNotNull(r?.id) },
            onVersions = { val r = contextMenuFor; contextMenuFor = null; versionsRow = r },
        )
    }

    // ── Loupe + its sheets ───────────────────────────────────────────────────
    loupeIndex?.let { start ->
        LoupeViewer(
            items = items,
            initialIndex = start,
            component = component,
            onDismiss = { loupeIndex = null },
            onEdit = { row ->
                loupeIndex = null
                component.onNavigate(
                    com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.RawEditor(
                        uri = android.net.Uri.parse(row.sourceUri),
                        projectContext = com.RAZStudio.StudioRoom.core.ui.utils
                            .navigation.ProjectPhotoRef(
                                projectId = row.projectId,
                                photoId = row.id,
                                displayName = row.displayName,
                            ),
                    )
                )
            },
            onShowInfo = { infoRow = it },
            onShowKeywords = { keywordTargets = listOf(it.id) },
            onShowVersions = { versionsRow = it },
            onCompare = { index -> loupeIndex = null; compareIndex = index },
        )
    }
    compareIndex?.let { pinned ->
        CompareViewer(
            items = items,
            pinnedIndex = pinned,
            component = component,
            onDismiss = { compareIndex = null },
        )
    }
    infoRow?.let { PhotoInfoSheet(row = it, onDismiss = { infoRow = null }) }
    if (keywordTargets.isNotEmpty()) {
        KeywordsSheet(
            component = component,
            photoIds = keywordTargets,
            onDismiss = { keywordTargets = emptyList() },
        )
    }
    versionsRow?.let {
        VersionsSheet(component = component, row = it, onDismiss = { versionsRow = null })
    }

    if (confirmPurge.isNotEmpty()) {
        val all = confirmPurge == ALL_TRASH
        AlertDialog(
            onDismissRequest = { confirmPurge = emptySet() },
            title = { Text(stringResource(R.string.gallery_trash_confirm_title)) },
            text = { Text(stringResource(R.string.gallery_trash_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    if (all) component.emptyTrash() else component.purgeFromTrash(confirmPurge)
                    confirmPurge = emptySet()
                }) {
                    Text(
                        stringResource(R.string.gallery_trash_delete_forever),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = emptySet() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    ProjectPhotoExportBlocking(state = photoExportState, onCancel = component::cancelPhotoExport)

    if (showPhotoExport) {
        ProjectPhotoExportDialog(
            projectName = name,
            onDismiss = { showPhotoExport = false },
            onStart = { fmt, wm ->
                showPhotoExport = false
                component.exportPhotos(fmt, wm)
            },
        )
    }

    if (lensSheetForSelection) {
        val first = selection.firstOrNull()?.let { id ->
            (0 until items.itemCount).firstNotNullOfOrNull { i ->
                items.peek(i)?.takeIf { it.id == id }
            }
        }
        if (first != null) {
            LensProfileSheet(
                row = first,
                component = component,
                onDismiss = { lensSheetForSelection = false },
                selectionCount = selection.size,
            )
        } else {
            lensSheetForSelection = false
        }
    }

    lensSheetFor?.let { row ->
        LensProfileSheet(
            row = row,
            component = component,
            onDismiss = { lensSheetFor = null },
        )
    }

    if (pasteTargets.isNotEmpty()) {
        com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar.EditClipboard.entry
            ?.let { entry ->
                PasteCategoriesSheet(
                    entry = entry,
                    targetCount = (pasteTargets - entry.photoId).size,
                    targetKit = if (pasteTargets.size == 1) pasteTargetKit else null,
                    onApply = { cats ->
                        component.pasteEditSettings(pasteTargets, cats)
                        pasteTargets = emptySet()
                    },
                    onDismiss = { pasteTargets = emptySet() },
                )
            } ?: run { pasteTargets = emptySet() }
    }

    resetRow?.let { row ->
        // Req 14.21 — the confirmation SAYS history is retained; reset is not a
        // delete, it is a new neutral revision.
        AlertDialog(
            onDismissRequest = { resetRow = null },
            title = { Text(stringResource(R.string.gallery_menu_reset_edits)) },
            text = { Text(stringResource(R.string.gallery_reset_confirm, row.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    component.resetEdits(row.id)
                    resetRow = null
                }) { Text(stringResource(R.string.gallery_reset_do)) }
            },
            dismissButton = {
                TextButton(onClick = { resetRow = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** Which project-transfer the picker dialog is serving. */
private enum class TransferMode { Move, Copy }

/**
 * The multi-select action bar (Req 8.2/8.3/8.6). Icon order mirrors the
 * per-tile menu: flag, reject, clear-flag, rate, label ‖ move, copy ‖ remove.
 * "Paste settings" (Req 8.8) lands here with task 8a's EditClipboard.
 */
@Composable
private fun BatchActionBar(
    onFlag: () -> Unit,
    onRate: () -> Unit,
    onLabel: () -> Unit,
    onKeywords: () -> Unit,
    onLensProfile: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    /** Non-null only while the edit clipboard holds settings (Req 14.18). */
    onPaste: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    androidx.compose.material3.Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onFlag) {
                Icon(Icons.Outlined.Flag, stringResource(R.string.gallery_batch_flag))
            }
            IconButton(onClick = onRate) {
                Icon(Icons.Rounded.Star, stringResource(R.string.gallery_batch_rate))
            }
            IconButton(onClick = onLabel) {
                Icon(Icons.Rounded.Label, stringResource(R.string.gallery_batch_label))
            }
            IconButton(onClick = onKeywords) {
                Icon(Icons.Rounded.Label, stringResource(R.string.gallery_keywords))
            }
            IconButton(onClick = onLensProfile) {
                Icon(Icons.Outlined.CameraAlt, stringResource(R.string.gallery_menu_lens_profile))
            }
            IconButton(onClick = onMove) {
                Icon(Icons.Rounded.Folder, stringResource(R.string.gallery_batch_move))
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, stringResource(R.string.gallery_batch_copy))
            }
            if (onPaste != null) {
                IconButton(onClick = onPaste) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = stringResource(R.string.gallery_menu_paste_settings),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.gallery_batch_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Sort ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    state: GridViewState,
    onPick: (GridSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.gallery_sort),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            GridSort.entries.forEach { sort ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { onPick(sort, state.descending) },
                        label = { Text(sortLabel(sort)) },
                    )
                    if (state.sort == sort) {
                        Spacer(Modifier.width(8.dp))
                        // Direction is a property of the chosen order, so it is
                        // offered next to it rather than as a separate global.
                        AssistChip(
                            onClick = { onPick(sort, !state.descending) },
                            label = {
                                Text(
                                    stringResource(
                                        if (state.descending) R.string.gallery_sort_desc
                                        else R.string.gallery_sort_asc
                                    )
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Filter ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    state: GridViewState,
    component: GalleryProjectComponent,
    onDismiss: () -> Unit,
) {
    val f = state.filter
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.gallery_filter),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                FilterChip(
                    selected = f.showUnflagged,
                    onClick = {
                        component.setFlagFilter(!f.showUnflagged, f.showFlagged, f.showRejected)
                    },
                    label = { Text(stringResource(R.string.gallery_flag_none)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = f.showFlagged,
                    onClick = {
                        component.setFlagFilter(f.showUnflagged, !f.showFlagged, f.showRejected)
                    },
                    label = { Text(stringResource(R.string.gallery_flag_flagged)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = f.showRejected,
                    onClick = {
                        component.setFlagFilter(f.showUnflagged, f.showFlagged, !f.showRejected)
                    },
                    label = { Text(stringResource(R.string.gallery_flag_rejected)) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.gallery_filter_min_rating),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                for (stars in 0..5) {
                    RatingChip(
                        stars = stars,
                        selected = f.minRating == stars,
                        onClick = { component.setMinRatingFilter(stars) },
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.gallery_filter_color),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                FilterChip(
                    selected = f.colorLabel == 0,
                    onClick = { component.setColorFilter(0) },
                    // "Any", not "No label" — as a FILTER, an unset colour means
                    // every photo passes, not only the unlabelled ones.
                    label = { Text(stringResource(R.string.gallery_filter_any)) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
                for (label in 1..COLOR_LABELS.size) {
                    Spacer(Modifier.width(4.dp))
                    ColorLabelChip(
                        label = label,
                        selected = f.colorLabel == label,
                        onClick = { component.setColorFilter(label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = component::clearFilter) {
                Text(stringResource(R.string.gallery_filter_clear))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Tile ─────────────────────────────────────────────────────────────────────

@Composable
private fun PhotoGridTile(
    row: PhotoGridRow,
    highlightAlpha: Float,
    selectionMode: Boolean,
    selected: Boolean,
    /** Req 13.10 — suppresses the per-tile pending spinner. */
    reduceMotion: Boolean,
    onToggleSelect: () -> Unit,
    onOverflow: () -> Unit,
    /** Requirement 9.1 — tap-to-edit; opens `Screen.RawEditor` with Project context. */
    onOpen: () -> Unit,
    onFlag: () -> Unit,
    onReject: () -> Unit,
    onRate: (Int) -> Unit,
) {
    // Theme colour rather than a hardcoded one, so the highlight stays legible in
    // both light and dark (Requirement 16.5a).
    val highlightColor = MaterialTheme.colorScheme.primary

    val flagged = row.flagState == GalleryProjectComponent.FLAG_FLAGGED
    val rejected = row.flagState == GalleryProjectComponent.FLAG_REJECTED
    val edited = row.hasEdits == 1
    val sidecarInApp = row.sidecarBesideOriginal == 0

    // Requirement 13.5 — one sentence carrying everything the tile shows. Tiny
    // indicator dots convey nothing to a screen reader on their own.
    val flagWord = stringResource(
        when {
            flagged -> R.string.gallery_flag_flagged
            rejected -> R.string.gallery_flag_rejected
            else -> R.string.gallery_flag_none
        }
    )
    // Req 13.5 — one utterance carrying filename, capture date, rating, flag,
    // has-edits, colour label, and reachability. The filename is wrapped in
    // FSI/PDI isolates so an LTR name inside an RTL sentence keeps its own
    // internal order (Req 13.3); the separator is a resource, not ", ".
    val msgSeparator = stringResource(R.string.gallery_msg_separator)
    val description = buildList {
        add("⁨${row.displayName}⁩")   // FSI/PDI bidi isolation (Req 13.3)
        add(formatLabel(row.sourceFormat))
        row.capturedAt?.let {
            add(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                .format(java.util.Date(it)))
        }
        add(flagWord)
        if (row.rating > 0) {
            add(pluralStringResource(R.plurals.gallery_rating_stars, row.rating, row.rating))
        }
        if (row.colorLabel in 1..COLOR_LABEL_NAMES.size) {
            add(stringResource(COLOR_LABEL_NAMES[row.colorLabel - 1]))
        }
        if (edited) add(stringResource(R.string.gallery_tile_edited))
        if (sidecarInApp) add(stringResource(R.string.gallery_tile_sidecar_in_app))
        if (row.sourceIsCopy) add(stringResource(R.string.gallery_stored_in_app))
        if (!row.uriPermissionOk) add(stringResource(R.string.gallery_tile_unreachable))
    }.joinToString(msgSeparator)

    val flagAction = stringResource(
        if (flagged) R.string.gallery_action_unflag else R.string.gallery_action_flag
    )
    val rejectAction = stringResource(
        if (rejected) R.string.gallery_action_unreject else R.string.gallery_action_reject
    )
    val rate3 = stringResource(R.string.gallery_action_rate, 3)
    val rate5 = stringResource(R.string.gallery_action_rate, 5)
    val rateClear = stringResource(R.string.gallery_action_rate_clear)
    val selectWord = stringResource(R.string.gallery_action_select)
    val selectedWord = stringResource(R.string.gallery_tile_selected)
    val notSelectedWord = stringResource(R.string.gallery_tile_not_selected)
    val openWord = stringResource(R.string.gallery_action_open_editor)
    val relinkWord = stringResource(R.string.gallery_menu_relink)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (highlightAlpha > 0f) {
                    Modifier.border(
                        width = 3.dp,
                        color = highlightColor.copy(alpha = highlightAlpha),
                        shape = RoundedCornerShape(6.dp),
                    )
                } else Modifier
            )
            // Requirement 7.8 — swipe up flags, swipe down rejects. Vertical only,
            // so the grid's own vertical scroll is the thing being competed with;
            // detectVerticalDragGestures consumes the drag once it wins, which is
            // why the tile does not scroll and flag at the same time.
            // DISABLED in multi-select: an accidental swipe while herding a
            // selection would silently flag/reject one photo.
            .pointerInput(row.id, selectionMode) {
                if (selectionMode) return@pointerInput
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = {
                        when {
                            dy < -SWIPE_THRESHOLD_PX -> onFlag()
                            dy > SWIPE_THRESHOLD_PX -> onReject()
                        }
                    },
                ) { _, amount -> dy += amount }
            }
            // Req 8.1 — long-press enters (or extends) multi-select; while
            // active, a plain tap toggles. The platform convention (Photos,
            // Files, Gmail), and the reason the context menu lives on ⋮ instead.
            // Req 9.1 — outside multi-select, a plain tap is tap-to-edit.
            .pointerInput(row.id, selectionMode) {
                detectTapGestures(
                    // Outside selection mode long-press ENTERS it. Inside, the
                    // tile deliberately does NOT handle long-press: the grid's
                    // drag-select owns it, and a child that consumed the press
                    // would stop the drag from ever starting.
                    onLongPress = if (selectionMode) null else ({ onToggleSelect() }),
                    // Requirement 10.2/10.5 — an unreachable photo has nothing
                    // an editor could decode, so a tap opens the context menu
                    // (Relink is its only enabled action) instead of a dead URI.
                    onTap = {
                        when {
                            selectionMode -> onToggleSelect()
                            row.uriPermissionOk -> onOpen()
                            else -> onOverflow()
                        }
                    },
                )
            }
            // Requirement 7.9 / 13.4 — the same operations as declared
            // accessibility actions. A swipe-only affordance is unreachable with
            // TalkBack or switch access, so the gesture can never be the only way.
            // mergeDescendants: without it the tile's child texts (placeholder
            // format label, "Unreachable") publish competing nodes and TalkBack
            // announces the tile twice (Req 13.5).
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (selectionMode) stateDescription = if (selected) selectedWord else notSelectedWord
                if (!selectionMode) {
                    if (row.uriPermissionOk) {
                        onClick(label = openWord) { onOpen(); true }
                    } else {
                        onClick(label = relinkWord) { onOverflow(); true }
                    }
                }
                customActions = listOf(
                    CustomAccessibilityAction(selectWord) { onToggleSelect(); true },
                    CustomAccessibilityAction(flagAction) { onFlag(); true },
                    CustomAccessibilityAction(rejectAction) { onReject(); true },
                    CustomAccessibilityAction(rate3) { onRate(3); true },
                    CustomAccessibilityAction(rate5) { onRate(5); true },
                    CustomAccessibilityAction(rateClear) { onRate(0); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            // The generated 640 px thumbnail. Never the source: a RAW cannot be
            // decoded by the platform at all, and a full-resolution JPEG decode
            // per tile is what the worker exists to avoid.
            //
            // Requirement 10.4 — an unreachable photo keeps its CACHED
            // thumbnail rather than vanishing; unreachability is layered on
            // top as a scrim + badge below, not substituted for the image.
            row.thumbPath != null -> AsyncImage(
                // The edited-thumbnail refresh rewrites the SAME file path, so a
                // path-only model would keep serving the pre-edit tile from
                // Coil's memory/disk caches. Key on generatedAt as well.
                model = coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(File(row.thumbPath))
                    .memoryCacheKey("${row.thumbPath}@${row.thumbGeneratedAt ?: 0L}")
                    .diskCacheKey("${row.thumbPath}@${row.thumbGeneratedAt ?: 0L}")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // No cached thumbnail at all (never rendered, or unreachable
            // before the worker got to it) — the unreachable state IS the
            // whole tile in that case. maxLines+ellipsis: the tile is a fixed
            // square, so at 6 columns × large font the text must clip
            // gracefully (Req 13.9) — the word is still in the tile's merged
            // content description regardless.
            !row.uriPermissionOk -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.BrokenImageAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.gallery_tile_unreachable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Requirement 6.9 — format + name placeholder while (or if) the
            // worker has nothing. Req 13.10: an endlessly spinning indicator on
            // EVERY pending tile turns a scrolling grid into a field of motion,
            // so it is replaced by the static placeholder under reduced motion.
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!reduceMotion) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = formatLabel(row.sourceFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Requirement 10.4 — scrim + badge OVER the kept thumbnail, rather
        // than replacing it (see the `when` above for the no-thumbnail case).
        if (!row.uriPermissionOk && row.thumbPath != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImageAlt,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.gallery_tile_unreachable),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ── Indicators (Requirement 7.5) ─────────────────────────────────────
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (flagged) TileBadge(Icons.Outlined.Flag, MaterialTheme.colorScheme.primary)
            if (rejected) TileBadge(Icons.Rounded.Block, MaterialTheme.colorScheme.error)
            if (edited) TileBadge(Icons.Rounded.Star, MaterialTheme.colorScheme.tertiary)
            if (row.sourceIsCopy) {
                // Requirement 16.16 — a Copied_Original behaves differently on
                // removal from a linked photo, so the difference has to be
                // visible on the tile, not only in the accessibility text.
                TileBadge(Icons.Rounded.Storage, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sidecarInApp) {
                // Requirement 4.7 — edits are NOT going beside the original.
                // Non-blocking, but the user has to be able to see it.
                TileBadge(Icons.Rounded.Label, MaterialTheme.colorScheme.secondary)
            }
        }

        // Stack badge: this tile stands for a RAW+JPEG pair (or a burst sharing
        // a basename). Shown top-END so it never collides with the flag/edit
        // indicators on the start side.
        if (row.stackCount > 1) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            ) {
                Text(
                    text = "×" + row.stackCount,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        // ── Selection visuals (Req 8.1) ──────────────────────────────────────
        if (selectionMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                        else Color.Black.copy(alpha = 0.25f)
                    )
            )
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckCircle
                              else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp),
            )
        }

        // Task 8a.1 — the ⋮ overflow now opens the PhotoContextMenu bottom
        // sheet (the DropdownMenu it replaces lives on in git history). Still
        // the non-gesture path to every tile action; hidden during multi-select
        // where the whole tile surface is a selection toggle.
        // Req 13.11 — the visible button stays compact (28dp) so it doesn't
        // cover the photo, but minimumInteractiveComponentSize floors its
        // TOUCH target at 48dp; this was the hardest-to-hit control with the
        // most consequence (the only route to relink/remove/reset).
        if (!selectionMode) Box(modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(
                onClick = onOverflow,
                modifier = Modifier
                    .size(28.dp)
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(
                        R.string.gallery_tile_actions, row.displayName,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Req 14.12b — persistent "copied settings" indicator on the source tile.
        if (com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar
                .EditClipboard.sourcePhotoId == row.id
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.gallery_tile_copied_settings),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(14.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.colorLabel in 1..COLOR_LABELS.size) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(COLOR_LABELS[row.colorLabel - 1])
                )
                Spacer(Modifier.width(3.dp))
            }
            if (row.rating > 0) {
                Text(
                    text = "★".repeat(row.rating),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TileBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(12.dp),
    )
    Spacer(Modifier.width(2.dp))
}

// ── Labels ───────────────────────────────────────────────────────────────────

@Composable
private fun describeFilter(state: GridViewState): String {
    val f = state.filter
    val flags = buildList {
        if (f.showUnflagged) add(stringResource(R.string.gallery_flag_none))
        if (f.showFlagged) add(stringResource(R.string.gallery_flag_flagged))
        if (f.showRejected) add(stringResource(R.string.gallery_flag_rejected))
    }
    // Localized separators + a plural rating description instead of the old
    // hardcoded " / ", "★ n+", " · ", "—" assembly (Req 13.1/13.3): the result
    // is injected into gallery_filter_active, so its own structure has to be
    // translatable too.
    val listSeparator = stringResource(R.string.gallery_list_separator)
    return buildList {
        // Selecting all three flag states filters nothing, so it is not worth
        // naming as a reason the grid is empty.
        if (flags.isNotEmpty() && flags.size < 3) add(flags.joinToString(listSeparator))
        if (f.minRating > 0) {
            add(pluralStringResource(R.plurals.gallery_filter_min_rating_desc, f.minRating, f.minRating))
        }
        if (f.colorLabel in 1..COLOR_LABEL_NAMES.size) {
            add(stringResource(COLOR_LABEL_NAMES[f.colorLabel - 1]))
        }
    }.joinToString(listSeparator).ifBlank { stringResource(R.string.gallery_filter_none) }
}

@Composable
private fun sortLabel(sort: GridSort): String = stringResource(
    when (sort) {
        GridSort.CapturedDate -> R.string.gallery_sort_captured
        GridSort.AddedDate -> R.string.gallery_sort_added
        GridSort.FileName -> R.string.gallery_sort_name
        GridSort.Format -> R.string.gallery_sort_format
        GridSort.Rating -> R.string.gallery_sort_rating
    }
)

/**
 * Human label for a PhotoFormat ordinal, for the tile and the failure
 * placeholder. The six format names are proper nouns and stay untranslated;
 * the unknown fallback is a resource (Req 13.1) since it IS user-visible.
 */
@Composable
private fun formatLabel(ordinal: Int): String = when (ordinal) {
    1 -> "JPEG"
    2 -> "WEBP"
    3 -> "BMP"
    4 -> "PNG"
    5 -> "TIFF"
    6 -> "RAW"
    else -> stringResource(R.string.gallery_format_unknown)
}

/**
 * A star-rating chip with a 48dp touch target (Req 13.11) and a spoken label
 * (Req 13.6) — the visual "★n" glyph run means nothing to TalkBack and
 * reorders in RTL (Req 13.3), so the semantics REPLACE it with a plural
 * "n stars" / "Clear rating". Shared by the batch-rate dialog, the filter
 * sheet, and the photo context menu.
 */
@Composable
internal fun RatingChip(stars: Int, selected: Boolean, onClick: () -> Unit) {
    val spoken = if (stars == 0) stringResource(R.string.gallery_action_rate_clear)
    else pluralStringResource(R.plurals.gallery_rating_stars, stars, stars)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (stars == 0) "—" else "★$stars") },
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = spoken },
    )
}

/**
 * A colour-label chip whose only visual content is a colour swatch — which is
 * exactly why it needs an explicit spoken label (Req 13.6: an unlabeled
 * selectable is unusable with TalkBack) and a 48dp touch floor (Req 13.11).
 */
@Composable
internal fun ColorLabelChip(label: Int, selected: Boolean, onClick: () -> Unit) {
    val spoken = stringResource(
        R.string.gallery_action_color, stringResource(COLOR_LABEL_NAMES[label - 1]),
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(COLOR_LABELS[label - 1])
            )
        },
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = spoken },
    )
}

/**
 * The five Lightroom colour labels, in Lightroom's order. Fixed rather than
 * theme-derived: the label is the user's own classification and has to mean the
 * same thing in light and dark, and after a theme change. Internal: the
 * PhotoContextMenu's label chips share it.
 */
internal val COLOR_LABELS = listOf(
    Color(0xFFE53935), // red
    Color(0xFFFDD835), // yellow
    Color(0xFF43A047), // green
    Color(0xFF1E88E5), // blue
    Color(0xFF8E24AA), // purple
)

internal val COLOR_LABEL_NAMES = listOf(
    R.string.gallery_color_red,
    R.string.gallery_color_yellow,
    R.string.gallery_color_green,
    R.string.gallery_color_blue,
    R.string.gallery_color_purple,
)

private const val HIGHLIGHT_MS = 1500L

/** Vertical travel before a drag counts as a flag/reject rather than a scroll. */
private const val SWIPE_THRESHOLD_PX = 90f

/**
 * Photo id of the tile under [position] (grid-local coordinates), or null when
 * the finger is in a gap or past the end. Reads the live layout info rather than
 * computing a row/column from the drag start, so it stays right while the grid
 * scrolls under the finger.
 */
private fun hitTestPhotoId(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    position: androidx.compose.ui.geometry.Offset,
    items: androidx.paging.compose.LazyPagingItems<PhotoGridRow>,
): Long? {
    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val x = position.x.toInt(); val y = position.y.toInt()
        x >= item.offset.x && x < item.offset.x + item.size.width &&
            y >= item.offset.y && y < item.offset.y + item.size.height
    } ?: return null
    return items.peek(info.index)?.id
}

/** Top-bar search box. Live — every keystroke re-queries through the pager. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(R.string.gallery_search_hint),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, stringResource(R.string.gallery_search_clear))
            }
        },
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/**
 * Send the photo's ORIGINAL file to another app. The grid links files it does
 * not own, so the URI is handed over with a read grant rather than copied; a
 * source whose grant has lapsed is skipped by the caller (the entry is disabled
 * for unreachable rows).
 */
private fun shareSourcePhoto(context: android.content.Context, row: PhotoGridRow) {
    val uri = runCatching { android.net.Uri.parse(row.sourceUri) }.getOrNull() ?: return
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "image/*"
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        type = mime
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, context.getString(R.string.share))
        )
    }
}

/** Height reserved for [SearchFacetChips] so the grid is not overlapped by it. */
private val FACET_ROW_HEIGHT = 44.dp

/**
 * One scrolling row of "what is actually in here": camera bodies, lenses and
 * keywords, most-used first, each tapping into the search box (tapping the
 * active one clears it). Built from the schema-v3 columns, so the list is the
 * scope's real content rather than a fixed menu.
 */
@Composable
private fun SearchFacetChips(
    cameras: List<String>,
    lenses: List<String>,
    keywords: List<Pair<String, Int>>,
    active: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cameras.isEmpty() && lenses.isEmpty() && keywords.isEmpty()) return
    androidx.compose.material3.Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FACET_ROW_HEIGHT)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val facets = cameras.map { it to null } + lenses.map { it to null } +
                keywords.map { (tag, count) -> tag to count }
            facets.forEach { (label, count) ->
                FilterChip(
                    selected = active.equals(label, ignoreCase = true),
                    onClick = { onPick(if (active.equals(label, ignoreCase = true)) "" else label) },
                    label = {
                        Text(
                            if (count != null) "$label ($count)" else label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

/** Sentinel for "every trashed photo", as opposed to a selection. */
private val ALL_TRASH: Set<Long> = setOf(-1L)

/** Selection bar while the trash is showing: restore, or delete for real. */
@Composable
private fun TrashActionBar(
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    androidx.compose.material3.Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRestore) {
                Icon(Icons.Rounded.Restore, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.gallery_trash_restore))
            }
            TextButton(onClick = onDeleteForever) {
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.gallery_trash_delete_forever),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
