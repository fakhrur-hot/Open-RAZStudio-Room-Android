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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.FolderOpen
import com.RAZStudio.StudioRoom.core.resources.icons.Image
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FolderEntry
import com.RAZStudio.StudioRoom.feature.sd_card_browser.presentation.screenLogic.SdCardBrowserComponent

@Composable
fun SdCardBrowserScreen(component: SdCardBrowserComponent) {
    val state by component.uiState.collectAsStateWithLifecycle()

    when (val s = state) {
        is SdCardBrowserUiState.Empty -> EmptyStateContent()
        is SdCardBrowserUiState.Loading -> LoadingContent()
        is SdCardBrowserUiState.UsbPermissionRequested -> UsbPermissionContent()
        is SdCardBrowserUiState.UsbError -> UsbErrorContent(message = s.message)
        is SdCardBrowserUiState.Browsing -> BrowsingContent(
            state = s,
            onFolderTap = { folder ->
                component.onFolderTap(
                    FolderEntry.Directory(folder.name, folder.handle)
                )
            },
            onFileTap = { photo ->
                component.onFileTap(
                    FolderEntry.Cr2File(
                        photo.name,
                        photo.handle,
                        photo.isDualIso
                    )
                )
            },
        )
        is SdCardBrowserUiState.Blending -> BlendProgressContent(
            filename = s.filename,
            progress = s.progress,
        )
        is SdCardBrowserUiState.BlendError -> BlendErrorContent(
            filename = s.filename,
            message = s.message,
            onOpenSingleIso = { component.onOpenSingleIsoFallback(s.fallbackUri) },
        )
    }
}

@Composable
private fun UsbPermissionContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Waiting for USB permission…",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Allow StudioRoom to access the card reader in the system prompt",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun UsbErrorContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Couldn't open USB device",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun EmptyStateContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No SD Card Detected",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Insert an SD card via USB OTG reader to browse photos",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun BrowsingContent(
    state: SdCardBrowserUiState.Browsing,
    onFolderTap: (BrowserGridItem.Folder) -> Unit,
    onFileTap: (BrowserGridItem.Photo) -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidthDp >= 840 -> 7
        screenWidthDp >= 600 -> 5
        else -> 3
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = state.entries,
            key = { it.name },
        ) { item ->
            when (item) {
                is BrowserGridItem.Folder -> FolderGridCell(
                    folder = item,
                    onClick = { onFolderTap(item) },
                )
                is BrowserGridItem.Photo -> PhotoGridCell(
                    photo = item,
                    onClick = { onFileTap(item) },
                )
            }
        }
    }
}

@Composable
private fun FolderGridCell(
    folder: BrowserGridItem.Folder,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = folder.name,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = folder.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhotoGridCell(
    photo: BrowserGridItem.Photo,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        // Thumbnail or placeholder
        if (photo.thumbnail != null) {
            Image(
                bitmap = photo.thumbnail.asImageBitmap(),
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = photo.name,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ML badge top-left
        if (photo.showMlBadge) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            ) {
                Text("ML", fontSize = 9.sp)
            }
        }

        // Dual ISO badge top-right
        if (photo.isDualIso) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                Text("Dual ISO", fontSize = 9.sp)
            }
        }

        // Filename at bottom
        Text(
            text = photo.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .padding(2.dp),
        )
    }
}

@Composable
private fun BlendProgressContent(
    filename: String,
    progress: Float,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Processing $filename...",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .width(200.dp)
                    .padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun BlendErrorContent(
    filename: String,
    message: String,
    onOpenSingleIso: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Error processing $filename",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenSingleIso) {
                Text("Open in Single-ISO Mode")
            }
        }
    }
}
