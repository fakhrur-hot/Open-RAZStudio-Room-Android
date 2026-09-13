/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.ImageReset
import com.RAZStudio.StudioRoom.core.resources.icons.Visibility
import com.RAZStudio.StudioRoom.core.resources.icons.VisibilityOff
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedAlertDialog
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton

@Composable
fun ActionsTabContent(
    actionCards: List<ActionCard>,
    savedActionsSets: List<ActionsSet>,
    onEyeToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onApplyAll: () -> Unit,
    onSaveActions: (String) -> Unit,
    onDeleteActionsSet: (String) -> Unit,
    isApplyingAll: Boolean,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showBrowseDialog by rememberSaveable { mutableStateOf(false) }
    var saveNameText by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        // Apply All button at top (centered)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            EnhancedButton(
                onClick = onApplyAll,
                enabled = !isApplyingAll && actionCards.size > 1,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                if (isApplyingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.apply_all))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Card stack — newest first, Original at bottom
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(actionCards, key = { it.id }) { card ->
                if (card is ActionCard.Original) {
                    OriginalCardItem()
                } else {
                    ActionCardItem(
                        card = card,
                        onEyeToggle = { onEyeToggle(card.id) },
                        onDelete = { onDelete(card.id) },
                    )
                }
            }
        }

        // Bottom row: Browse | Save Actions (centered)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            EnhancedButton(
                onClick = { showBrowseDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.browse_actions),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EnhancedButton(
                onClick = {
                    saveNameText = ""
                    showSaveDialog = true
                },
                enabled = actionCards.size > 1,
            ) {
                Text(
                    text = stringResource(R.string.save_actions),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // Save Actions dialog
    EnhancedAlertDialog(
        visible = showSaveDialog,
        onDismissRequest = { showSaveDialog = false },
        title = { Text(stringResource(R.string.save_actions)) },
        text = {
            OutlinedTextField(
                value = saveNameText,
                onValueChange = { saveNameText = it },
                label = { Text(stringResource(R.string.actions_set_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            EnhancedButton(
                onClick = {
                    if (saveNameText.isNotBlank()) {
                        onSaveActions(saveNameText.trim())
                        showSaveDialog = false
                    }
                },
                enabled = saveNameText.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            EnhancedButton(
                onClick = { showSaveDialog = false },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    // Browse Actions dialog
    EnhancedAlertDialog(
        visible = showBrowseDialog,
        onDismissRequest = { showBrowseDialog = false },
        title = { Text(stringResource(R.string.browse_actions)) },
        text = {
            if (savedActionsSets.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_saved_actions),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(savedActionsSets, key = { it.name }) { set ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = set.name,
                                style = MaterialTheme.typography.bodyMedium,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            EnhancedIconButton(
                                onClick = { onDeleteActionsSet(set.name) },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            EnhancedButton(
                onClick = { showBrowseDialog = false },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun ActionCardItem(
    card: ActionCard,
    onEyeToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (card.isVisible) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnhancedIconButton(onClick = onEyeToggle) {
                Icon(
                    imageVector = if (card.isVisible) Icons.Rounded.Visibility
                    else Icons.Rounded.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = card.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            EnhancedIconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun OriginalCardItem() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.ImageReset,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.original),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
