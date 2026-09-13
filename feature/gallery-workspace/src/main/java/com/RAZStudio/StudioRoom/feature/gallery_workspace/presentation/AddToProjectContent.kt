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

import android.text.format.Formatter
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.Add
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.Image
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.AddToProjectComponent
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.AddToProjectStage
import com.RAZStudio.StudioRoom.feature.gallery_workspace.presentation.screenLogic.ProjectRow
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Project picker for "Add to project" (Requirement 16.2–16.13).
 *
 * Reached by navigation from `ImagePreview`, which therefore needs no database
 * dependency of its own (Requirement 16.24).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToProjectContent(component: AddToProjectComponent) {
    val projects by component.projects.collectAsState()
    val covers by component.covers.collectAsState()
    val stage by component.stage.collectAsState()
    val addAll by component.addAll.collectAsState()
    val context = LocalContext.current

    var showCreate by rememberSaveable { mutableStateOf(false) }

    // Requirement 16.3a / 13.2 — locale-aware date in the suggested name.
    val suggestedName = remember {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        context.getString(R.string.gallery_shared_photos_default_name, date)
    }

    Scaffold(
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
                title = { Text(stringResource(R.string.gallery_add_to_project)) },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // Requirement 16.6 — multi-share adds the previewed photo by
            // default, with adding all as an explicit alternative.
            if (component.incomingUris.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { component.setAddAll(!addAll) }
                        .padding(horizontal = 8.dp),
                ) {
                    Checkbox(checked = addAll, onCheckedChange = { component.setAddAll(it) })
                    Text(
                        text = stringResource(
                            R.string.gallery_add_all_shared,
                            component.incomingUris.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Requirement 16.3b — creation offered at all times, not only when
            // no project exists; a shared photo is a common reason to start one.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreate = true }
                    .padding(12.dp),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(
                        if (projects.isEmpty()) R.string.gallery_no_projects_create
                        else R.string.gallery_project_create
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectPickerRow(
                        project = project,
                        coverPath = covers[project.id],
                        onPick = { component.chooseProject(project.id, project.name) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        var name by rememberSaveable { mutableStateOf(suggestedName) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.gallery_project_create)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.gallery_project_name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreate = false
                        component.createProjectAndAdd(name, suggestedName)
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    when (val s = stage) {
        is AddToProjectStage.Working -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is AddToProjectStage.Disclose -> {
            // Requirement 16.11 / 16.11a — size AND free space now and after.
            // Size alone does not answer the question the user is asking.
            val size = Formatter.formatFileSize(context, s.plan.copyBytes)
            val now = Formatter.formatFileSize(context, s.plan.freeBytesNow)
            val after = Formatter.formatFileSize(context, s.plan.freeBytesAfter)
            AlertDialog(
                onDismissRequest = { component.declineDisclosure() },
                title = { Text(stringResource(R.string.gallery_copy_disclosure_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.gallery_copy_disclosure_body, size, now, after,
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { component.confirmDisclosure() }) {
                        Text(stringResource(R.string.gallery_copy_disclosure_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { component.declineDisclosure() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        is AddToProjectStage.Error -> {
            val message = when {
                s.message == "declined" -> stringResource(R.string.gallery_copy_declined)
                s.message.startsWith("no-space:") -> stringResource(
                    R.string.gallery_copy_no_space,
                    Formatter.formatFileSize(
                        context,
                        s.message.removePrefix("no-space:").toLongOrNull() ?: 0L,
                    ),
                )
                else -> stringResource(R.string.gallery_copy_failed, s.message)
            }
            AlertDialog(
                onDismissRequest = { component.dismissError() },
                title = { Text(stringResource(R.string.gallery_add_to_project)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { component.dismissError() }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        AddToProjectStage.Choosing -> Unit
    }
}

@Composable
private fun ProjectPickerRow(
    project: ProjectRow,
    coverPath: String?,
    onPick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick)
                .padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
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
            // Req 13.5/13.9 — one merged utterance with the FULL project name,
            // since the visual title may ellipsize.
            val rowDesc = pluralStringResource(
                R.plurals.gallery_project_row_description,
                project.photoCount, project.name, project.photoCount,
            )
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .clearAndSetSemantics { contentDescription = rowDesc },
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
                        project.photoCount, project.photoCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
