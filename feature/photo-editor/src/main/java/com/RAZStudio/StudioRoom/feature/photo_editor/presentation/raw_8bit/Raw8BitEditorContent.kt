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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw_8bit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack

/**
 * Phase 3 surface for the 8-bit RAW workspace.
 *
 *  - Empty state: a "Pick RAW" button that opens the SAF picker.
 *  - Processing: a spinner with the current stage label.
 *  - Ready: the tonemapped Bitmap preview + a "Save JPG" button.
 *  - Saved: same preview + a small "Saved to …" footer.
 *  - Error: the failure message + retry.
 *
 * No editor controls yet — Phase 4 brings the curves/exposure/etc.
 * sliders. This screen is the "prove the pipeline works end-to-end"
 * deliverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Raw8BitEditorContent(component: Raw8BitEditorComponent) {
    val state by component.state.collectAsState()

    val openDocPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) component.openFile(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("8-bit Workspace", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component.onGoBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is Raw8BitEditorComponent.UiState.Empty ->
                    EmptyState(onPick = { openDocPicker.launch(RAW_MIME_TYPES) })
                is Raw8BitEditorComponent.UiState.Processing ->
                    ProcessingState(stage = s.stage)
                is Raw8BitEditorComponent.UiState.Editing ->
                    EditingState(
                        bitmap = s.displayBitmap,
                        actionCount = s.actions.size,
                        onSave = component::save,
                        onPickAnother = { openDocPicker.launch(RAW_MIME_TYPES) },
                        onUndo = component::undo,
                    )
                is Raw8BitEditorComponent.UiState.Saved ->
                    SavedState(
                        bitmap = s.displayBitmap,
                        outPath = s.outFile.absolutePath,
                        onPickAnother = { openDocPicker.launch(RAW_MIME_TYPES) },
                    )
                is Raw8BitEditorComponent.UiState.Error ->
                    ErrorState(
                        message = s.message,
                        onPickAnother = { openDocPicker.launch(RAW_MIME_TYPES) },
                    )
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(
            "Open a RAW file to tonemap with segmented-CLAHE.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onPick) { Text("Pick RAW") }
    }
}

@Composable
private fun ProcessingState(stage: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text(
            stage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EditingState(
    bitmap: android.graphics.Bitmap,
    actionCount: Int,
    onSave: () -> Unit,
    onPickAnother: () -> Unit,
    onUndo: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Edit preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        // Phase 4.A foundation placeholder. Tabs (Light, Color,
        // Tonemap, Curves, Details, LUT) attach here in subsequent
        // sub-steps. The visible state below is just diagnostic — it
        // confirms the component is properly tracking the action stack
        // even though no tab UI is wired yet.
        Text(
            "Editor foundation ready · ${actionCount} action(s) on stack",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        if (actionCount > 0) {
            OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) {
                Text("Undo last edit")
            }
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("Save JPG")
        }
        OutlinedButton(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) {
            Text("Pick another RAW")
        }
    }
}

@Composable
private fun SavedState(
    bitmap: android.graphics.Bitmap,
    outPath: String,
    onPickAnother: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Saved preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            "Saved to: $outPath",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) {
            Text("Pick another RAW")
        }
    }
}

@Composable
private fun ErrorState(message: String, onPickAnother: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onPickAnother) { Text("Try another file") }
    }
}

private val RAW_MIME_TYPES = arrayOf(
    "image/x-canon-cr2",
    "image/x-canon-cr3",
    "image/x-nikon-nef",
    "image/x-sony-arw",
    "image/x-adobe-dng",
    "image/x-panasonic-raw",
    "image/x-fuji-raf",
    "image/x-olympus-orf",
    "image/x-pentax-pef",
    "image/x-samsung-srw",
    "image/tiff",
    "image/*",
)
