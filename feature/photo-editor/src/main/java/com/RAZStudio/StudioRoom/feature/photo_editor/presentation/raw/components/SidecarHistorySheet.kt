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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2-integration §2.2 — sidecar edit-history bottom sheet.
 *
 * Loads the current snapshot via [loadSidecar] on first composition. The list shows the
 * "Current" head + each stored revision; tapping a revision invokes [onRevert] (which
 * triggers the coordinator's revert + macro re-render path) and refreshes the sheet.
 *
 * @param loadSidecar  Suspend fetch the snapshot; null result hides the list.
 * @param onRevert     Restore a revision by index (0-based, 0 = most recent in history).
 *                     Returns the updated snapshot so the sheet refreshes in place.
 * @param onDismiss    Called when the user dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidecarHistorySheet(
    loadSidecar: suspend () -> SidecarSnapshot?,
    onRevert: suspend (Int) -> SidecarSnapshot?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SidecarSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        snapshot = loadSidecar()
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Edit history",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Restore an earlier version of your edits",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
                snapshot == null -> Text(
                    text = "No edit history found for this file yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val snap = snapshot!!
                    // Head row (current state). Tapping it is a no-op.
                    HistoryRow(
                        title = "Current",
                        subtitle = formatTimestamp(snap.lastEditedEpochMs),
                        isCurrent = true,
                        onClick = {},
                    )
                    if (snap.revisions.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No earlier revisions. Each committed action adds a checkpoint here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        snap.revisions.forEachIndexed { index, revision ->
                            Spacer(Modifier.height(6.dp))
                            HistoryRow(
                                title = "Revision ${snap.revisions.size - index}",
                                subtitle = formatTimestamp(revision.timestampEpochMs),
                                isCurrent = false,
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        val updated = onRevert(index)
                                        if (updated != null) snapshot = updated
                                        loading = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HistoryRow(
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val titleColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .then(if (isCurrent) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
