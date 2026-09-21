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

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyConnectionStep
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyConnectionType
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonySyncComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonySyncContent(component: SonySyncComponent) {
    val step by component.step.collectAsState()
    val running by component.running.collectAsState()
    val progress by component.progress.collectAsState()
    val result by component.result.collectAsState()
    val importSize by component.importSize.collectAsState()
    val connectionType by component.connectionType.collectAsState()
    val remoteActive by component.remoteActive.collectAsState()
    val cloudSettingsActive by component.cloudSettingsActive.collectAsState()
    val cloudUploadActive by component.cloudUploadActive.collectAsState()

    // Full-screen sub-panes take over when active.
    if (remoteActive) {
        SonyCameraRemotePane(controller = component.remote, onExit = component::exitRemote)
        return
    }
    if (cloudSettingsActive) {
        SonyCloudSettingsPane(cloud = component.cloud, onExit = component::closeCloudSettings)
        return
    }
    if (cloudUploadActive) {
        SonyCloudUploadPane(cloud = component.cloud, onExit = component::closeCloudUpload)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = component.onGoBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Sony Sync") },
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Camera connection: status + type + import size + download ──────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Camera connection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stepLabel(step),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val (done, total) = progress
                    if (running || total > 0) {
                        Spacer(Modifier.height(12.dp))
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { done.toFloat() / total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("$done / $total", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    // Connection type (picks the transport / discovery strategy).
                    Text(
                        text = "Connection Type",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    ConnectionRow(
                        selected = connectionType == SonyConnectionType.AUTO,
                        enabled = !running,
                        title = "Auto-detect (recommended)",
                        subtitle = "Picks the right Wi-Fi option automatically",
                        onClick = { component.setConnectionType(SonyConnectionType.AUTO) },
                    )
                    ConnectionRow(
                        selected = connectionType == SonyConnectionType.WIFI_OLDER,
                        enabled = !running,
                        title = "Wi-Fi · older models",
                        subtitle = "A7 II, α6000, RX",
                        onClick = { component.setConnectionType(SonyConnectionType.WIFI_OLDER) },
                    )
                    ConnectionRow(
                        selected = connectionType == SonyConnectionType.WIFI_NEWER,
                        enabled = !running,
                        title = "Wi-Fi · newer models",
                        subtitle = "A7 III, α9 and later",
                        onClick = { component.setConnectionType(SonyConnectionType.WIFI_NEWER) },
                    )
                    ConnectionRow(
                        selected = connectionType == SonyConnectionType.USB_MASS_STORAGE,
                        enabled = !running,
                        title = "USB cable",
                        subtitle = "Copies everything, including RAW",
                        onClick = { component.setConnectionType(SonyConnectionType.USB_MASS_STORAGE) },
                    )

                    // Import size — Wi-Fi paths only (USB copies card originals as-is).
                    if (connectionType != SonyConnectionType.USB_MASS_STORAGE) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            text = "Import size",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.FilterChip(
                                selected = importSize == com.RAZStudio.StudioRoom.feature.sony_sync.data
                                    .SonyCameraRemoteApi.ImportSize.ORIGINAL,
                                onClick = {
                                    component.setImportSize(
                                        com.RAZStudio.StudioRoom.feature.sony_sync.data
                                            .SonyCameraRemoteApi.ImportSize.ORIGINAL,
                                    )
                                },
                                enabled = !running,
                                label = { Text("Original (full)") },
                            )
                            androidx.compose.material3.FilterChip(
                                selected = importSize == com.RAZStudio.StudioRoom.feature.sony_sync.data
                                    .SonyCameraRemoteApi.ImportSize.TWO_M,
                                onClick = {
                                    component.setImportSize(
                                        com.RAZStudio.StudioRoom.feature.sony_sync.data
                                            .SonyCameraRemoteApi.ImportSize.TWO_M,
                                    )
                                },
                                enabled = !running,
                                label = { Text("2M (small)") },
                            )
                        }
                        Text(
                            text = "The camera must be set to send this size too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    // Connect & Download.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = component::connectAndDownload,
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (running) "Working…" else "Connect & Download") }
                        if (running) {
                            OutlinedButton(
                                onClick = component::cancel,
                                modifier = Modifier.weight(1f),
                            ) { Text("Cancel") }
                        }
                    }
                }
            }

            // ── Camera Remote (USB PC-Remote live shooting) ───────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Camera Remote (USB)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Live view and controls over USB. Set the camera to PC Remote first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = component::enterRemote,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open Camera Remote") }
                }
            }

            // ── Live upload to cloud ──────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Share to Cloud (live upload)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Each photo uploads to a cloud folder with a shareable QR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = component::openCloudSettings, enabled = !running, modifier = Modifier.weight(1f)) {
                            Text("Cloud Settings")
                        }
                        Button(onClick = component::openCloudUpload, enabled = !running, modifier = Modifier.weight(1f)) {
                            Text("Share to Cloud")
                        }
                    }
                }
            }

            // ── Result banner (success with folder, or failure reason) ─────────
            result?.let { r ->
                val success = r is com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyResult.Success
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (success) MaterialTheme.colorScheme.primaryContainer
                                         else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        when (r) {
                            is com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyResult.Success -> {
                                Text(
                                    "✓ Pulled ${r.downloaded} of ${r.total} photo(s)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "Saved to: ${r.folder}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            is com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyResult.Failed -> {
                                Text(
                                    "✕ Transfer failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    r.reason,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── How it works (short) ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "How to connect",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    HowToStep(1, "Turn on the camera's Wi-Fi and join it on your phone (or connect by USB).")
                    HowToStep(2, "Pick an output folder in Settings.")
                    HowToStep(3, "Tap Connect & Download.")
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected, enabled = enabled, onClick = null,
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HowToStep(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun stepLabel(step: SonyConnectionStep): String = when (step) {
    SonyConnectionStep.Idle -> "Not connected"
    SonyConnectionStep.WaitingForNfcTap -> "Waiting for camera…"
    SonyConnectionStep.JoiningSoftAp -> "Connecting…"
    SonyConnectionStep.Discovering -> "Finding camera…"
    SonyConnectionStep.Connected -> "Connected"
}
