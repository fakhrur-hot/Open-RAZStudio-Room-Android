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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// The project ships its own icon set (core.resources.Icons + extension
// properties) rather than depending on androidx material-icons, which is not on
// this module's classpath. Same convention as feature/sony-sync.
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.Add
import com.RAZStudio.StudioRoom.core.resources.icons.AddPhotoAlt
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.FileExport
import com.RAZStudio.StudioRoom.core.resources.icons.FileImport
import com.RAZStudio.StudioRoom.core.resources.icons.FileRename
import com.RAZStudio.StudioRoom.core.resources.icons.Folder
import com.RAZStudio.StudioRoom.core.resources.icons.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.width
import com.RAZStudio.StudioRoom.core.resources.icons.GridOn
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoImporter
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.GalleryWorkspaceComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.ProjectRow
import java.io.File

/**
 * Project list — the Gallery Workspace landing screen (Requirements 1 and 2).
 *
 * Deliberately thin: it renders projects and raises lifecycle intents. Photos,
 * thumbnails, and the grid all live behind a project, not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryWorkspaceContent(component: GalleryWorkspaceComponent) {
    val projects by component.projects.collectAsState()
    val covers by component.covers.collectAsState()
    val lastImport by component.lastImport.collectAsState()
    val lastMigration by component.lastMigration.collectAsState()
    val revokedGrants by component.revokedGrants.collectAsState()
    val importing by component.importing.collectAsState()
    val exporting by component.exporting.collectAsState()
    val lastExport by component.lastExport.collectAsState()
    val importingProject by component.importingProject.collectAsState()
    val lastProjectImport by component.lastProjectImport.collectAsState()
    val lastError by component.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Localized list separator — never a hardcoded " · " (Req 13.1/13.3).
    val listSeparator = stringResource(R.string.gallery_list_separator)

    // Requirement 3.7 — report added/skipped counts, and 3.3 — say when photos
    // came in without a persistable grant, since those will need relinking.
    val importedMsg = lastImport?.let {
        stringResource(R.string.gallery_import_result, it.added, it.skipped)
    }
    val noPermMsg = lastImport?.takeIf { it.withoutPermission > 0 }?.let {
        pluralStringResource(
            R.plurals.gallery_import_no_permission, it.withoutPermission, it.withoutPermission,
        )
    }
    val migrationMsg = lastMigration?.let { r ->
        buildList {
            add(pluralStringResource(R.plurals.gallery_migration_result, r.migrated, r.migrated))
            if (r.failed > 0) add(stringResource(R.string.gallery_migration_failed, r.failed))
        }.joinToString(listSeparator)
    }
    LaunchedEffect(lastMigration) {
        if (migrationMsg != null && lastMigration?.touchedAnything == true) {
            snackbarHostState.showSnackbar(migrationMsg)
        }
        if (lastMigration != null) component.consumeLastMigration()
    }

    val revokedMsg = stringResource(R.string.gallery_grant_revoked)
    LaunchedEffect(revokedGrants) {
        if (revokedGrants.isNotEmpty()) {
            snackbarHostState.showSnackbar(revokedMsg)
            component.consumeRevokedGrants()
        }
    }

    LaunchedEffect(lastImport) {
        if (importedMsg != null) {
            snackbarHostState.showSnackbar(
                listOfNotNull(importedMsg, noPermMsg).joinToString(listSeparator)
            )
            component.consumeLastImport()
        }
    }

    // Requirement 13.8 — surface component-level failures (create/delete/
    // import/export/grant). This flow was previously never collected, so
    // every one of those failures was silent.
    val errorMsg = lastError?.let { stringResource(R.string.gallery_generic_error, it) }
    LaunchedEffect(lastError) {
        if (errorMsg != null) {
            snackbarHostState.showSnackbar(errorMsg)
            component.consumeError()
        }
    }

    // Requirement 12.1/12.2 — report what an export actually wrote.
    val exportMsg = lastExport?.let {
        pluralStringResource(
            R.plurals.gallery_export_result, it.exportedPhotos,
            it.exportedPhotos, it.withSidecar, it.withThumbnail,
        )
    }
    LaunchedEffect(lastExport) {
        if (exportMsg != null) {
            snackbarHostState.showSnackbar(exportMsg)
            component.consumeLastExport()
        }
    }

    // Requirement 12.5 — a project import always tells the user how many
    // photos came back unreachable, since those need the Relink flow next.
    val projectImportMsg = lastProjectImport?.let {
        if (it.projectId == null) {
            stringResource(R.string.gallery_project_import_failed)
        } else if (it.unreachable > 0) {
            pluralStringResource(
                R.plurals.gallery_project_import_result_unreachable,
                it.imported, it.imported, it.unreachable,
            )
        } else {
            pluralStringResource(R.plurals.gallery_project_import_result, it.imported, it.imported)
        }
    }
    LaunchedEffect(lastProjectImport) {
        if (projectImportMsg != null) {
            snackbarHostState.showSnackbar(projectImportMsg)
            component.consumeLastProjectImport()
        }
    }

    // Which project an in-flight import is destined for. Held outside the
    // launcher because the SAF result arrives after the row is long gone.
    var importTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    val importer = remember { PhotoImporter() }
    val importLauncher = importer.rememberLauncher { uris ->
        importTarget?.let { component.importPhotos(it, uris) }
        importTarget = null
    }

    // Folder-grant flow (Requirement 4.3/4.4). Held per project because the SAF
    // result arrives long after the row that started it is gone.
    var grantTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var showGrantPrompt by rememberSaveable { mutableStateOf<Long?>(null) }
    var showGrantExplainer by rememberSaveable { mutableStateOf(false) }
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val tree = result.data?.data
        val target = grantTarget
        if (tree != null && target != null) component.onFolderGranted(target, tree)
        grantTarget = null
    }

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renameTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    // ── Export photos (owner request 2026-09-07) ────────────────────────────
    // The card's export action bakes every photo's sidecar to a finished file
    // (see ProjectPhotoExporter). The old project-bundle export dialog was
    // removed from here; the bundle format is still read by the import action.
    var showPhotoExportFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val photoExportState by component.photoExportState.collectAsState()
    val exportScope = rememberCoroutineScope()
    val storage by component.storage.collectAsState()
    val allPhotosCount = projects.sumOf { it.photoCount }
    LaunchedEffect(projects.size) { component.refreshStorage() }
    val busyText = stringResource(R.string.gallery_export_photos_busy)
    val emptyText = stringResource(R.string.gallery_export_photos_empty)

    // ── Project import (Requirement 12.5) ────────────────────────────────────
    var awaitingImportPick by rememberSaveable { mutableStateOf(false) }
    val importDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val source = result.data?.data
        if (awaitingImportPick && source != null) component.importProject(source)
        awaitingImportPick = false
    }

    Scaffold(
        // Req 13.8 — async outcomes (import/export/migration/errors) land in
        // this host; the polite live region makes TalkBack announce them even
        // when focus is elsewhere, which a bare Snackbar does not guarantee.
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = component.onGoBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.exit),
                        )
                    }
                },
                title = { Text(stringResource(R.string.gallery_workspace)) },
                actions = {
                    IconButton(onClick = {
                        awaitingImportPick = true
                        importDirLauncher.launch(component.projectTransferDirIntent())
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.FileImport,
                            contentDescription = stringResource(R.string.gallery_project_import_bundle),
                        )
                    }
                },
            )
        },
        bottomBar = {
            ProjectPhotoExportCard(
                state = photoExportState,
                onCancel = component::cancelPhotoExport,
                onDismiss = component::dismissPhotoExport,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.gallery_project_create),
                )
            }
        },
    ) { inner ->
        if (importing || exporting || importingProject) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(inner),
            )
        }
        if (projects.isEmpty()) {
            EmptyProjectsState(
                modifier = Modifier.padding(inner),
                onCreate = { showCreate = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Library-wide entry: the same grid screen with the project
                // predicate dropped (GalleryProjectComponent.ALL_PHOTOS_ID), so
                // search, filters, loupe and batch actions all work across
                // projects without a second screen.
                item(key = "all_photos") {
                    AllPhotosRow(
                        count = allPhotosCount,
                        onOpen = {
                            component.onNavigate(
                                Screen.GalleryProject(
                                    projectId = com.RAZStudio.StudioRoom.feature.gallery_workspace
                                        .presentation.screenLogic.GalleryProjectComponent.ALL_PHOTOS_ID,
                                )
                            )
                        },
                    )
                }
                item(key = "storage") {
                    StorageCard(stats = storage, onClearCache = component::clearDecodeCache)
                }
                items(projects, key = { it.id }) { project ->
                    ProjectListRow(
                        project = project,
                        coverPath = covers[project.id],
                        onOpen = {
                            component.onNavigate(
                                Screen.GalleryProject(projectId = project.id)
                            )
                        },
                        onGrantFolder = { showGrantPrompt = project.id },
                        onImport = {
                            importTarget = project.id
                            importLauncher.launch(importer.buildIntent())
                        },
                        onRename = { renameTarget = project.id },
                        onDelete = { deleteTarget = project.id },
                        onExport = { showPhotoExportFor = project.id },
                    )
                }
            }
        }
    }

    showGrantPrompt?.let { projectId ->
        AlertDialog(
            onDismissRequest = { showGrantPrompt = null },
            title = { Text(stringResource(R.string.gallery_folder_grant_title)) },
            text = {
                Column {
                    // Req 13.12 — name WHICH project this grant applies to; with
                    // several rows in the list a screen-reader user otherwise has
                    // no way to confirm what they triggered.
                    projects.firstOrNull { it.id == projectId }?.let { row ->
                        Text(stringResource(R.string.gallery_folder_grant_for_project, row.name))
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.gallery_folder_grant_body))
                    Spacer(Modifier.height(8.dp))
                    // Requirement 4.11 — an inline route to the reasoning, so a
                    // cautious user is not left guessing why a permission
                    // dialog appeared.
                    TextButton(onClick = { showGrantExplainer = true }) {
                        Text(stringResource(R.string.gallery_folder_grant_why))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    grantTarget = projectId
                    showGrantPrompt = null
                    treeLauncher.launch(component.folderGrantIntent())
                }) { Text(stringResource(R.string.gallery_folder_grant_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showGrantPrompt = null }) {
                    Text(stringResource(R.string.gallery_folder_grant_decline))
                }
            },
        )
    }

    if (showGrantExplainer) {
        AlertDialog(
            onDismissRequest = { showGrantExplainer = false },
            title = { Text(stringResource(R.string.gallery_folder_grant_why)) },
            // Req 13.9/13.12 — the explainer is multi-paragraph; without a
            // scroll the tail is clipped (and unreachable to a screen reader)
            // on short screens and at large font scale.
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.gallery_folder_grant_explainer))
                }
            },
            confirmButton = {
                TextButton(onClick = { showGrantExplainer = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showCreate) {
        ProjectNameDialog(
            titleRes = R.string.gallery_project_create,
            initialName = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                component.createProject(name)
                showCreate = false
            },
        )
    }

    renameTarget?.let { id ->
        ProjectNameDialog(
            titleRes = R.string.gallery_project_rename,
            initialName = projects.firstOrNull { it.id == id }?.name.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                component.renameProject(id, name)
                renameTarget = null
            },
        )
    }

    ProjectPhotoExportBlocking(state = photoExportState, onCancel = component::cancelPhotoExport)

    showPhotoExportFor?.let { projectId ->
        val projectName = projects.firstOrNull { it.id == projectId }?.name ?: ""
        ProjectPhotoExportDialog(
            projectName = projectName,
            onDismiss = { showPhotoExportFor = null },
            onStart = { fmt, wm ->
                showPhotoExportFor = null
                if (component.photoExportRunning) {
                    exportScope.launch { snackbarHostState.showSnackbar(busyText) }
                } else {
                    component.exportPhotos(projectId, fmt, wm) {
                        exportScope.launch { snackbarHostState.showSnackbar(emptyText) }
                    }
                }
            },
        )
    }

    deleteTarget?.let { id ->
        val row = projects.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.gallery_project_delete)) },
            text = {
                // Requirement 2.6 — the confirmation must state the photo count.
                // Requirement 2.8 — and be explicit that originals are untouched,
                // because "delete project" reads as "delete my photos" otherwise.
                Text(
                    pluralStringResource(
                        R.plurals.gallery_project_delete_confirm,
                        row?.photoCount ?: 0,
                        row?.name.orEmpty(),
                        row?.photoCount ?: 0,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    component.deleteProject(id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyProjectsState(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.gallery_workspace_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.gallery_workspace_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCreate) {
            Text(stringResource(R.string.gallery_project_create))
        }
    }
}

@Composable
private fun ProjectListRow(
    project: ProjectRow,
    coverPath: String?,
    onOpen: () -> Unit,
    onGrantFolder: () -> Unit,
    onImport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    // One description carrying everything the row conveys visually, so a screen
    // reader gets name and count as a single utterance rather than fragments
    // (Requirement 13.5).
    val rowDescription = pluralStringResource(
        R.plurals.gallery_project_row_description,
        project.photoCount,
        project.name,
        project.photoCount,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverPath != null) {
                    AsyncImage(
                        model = File(coverPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .clearAndSetSemantics { contentDescription = rowDescription },
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.gallery_project_photo_count,
                        project.photoCount,
                        project.photoCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 48 dp targets per Requirement 13.11.
            IconButton(onClick = onGrantFolder, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = stringResource(R.string.gallery_folder_grant_title),
                )
            }
            IconButton(onClick = onImport, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlt,
                    contentDescription = stringResource(R.string.gallery_project_import),
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.FileExport,
                    contentDescription = stringResource(R.string.gallery_export_photos),
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.FileRename,
                    contentDescription = stringResource(R.string.gallery_project_rename),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.gallery_project_delete),
                )
            }
        }
    }
}

@Composable
private fun ProjectNameDialog(
    titleRes: Int,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val blank = name.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = blank && name.isNotEmpty(),
                    label = { Text(stringResource(R.string.gallery_project_name_label)) },
                )
                if (blank && name.isNotEmpty()) {
                    // Requirement 2.2 — reject blank/whitespace inline rather
                    // than silently dropping the action.
                    Text(
                        text = stringResource(R.string.gallery_project_name_blank),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = !blank) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Library-wide entry at the head of the project list. */
@Composable
private fun AllPhotosRow(count: Int, onOpen: () -> Unit) {
    androidx.compose.material3.Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.GridOn,
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.gallery_all_photos),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    pluralStringResource(R.plurals.gallery_project_photo_count, count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
