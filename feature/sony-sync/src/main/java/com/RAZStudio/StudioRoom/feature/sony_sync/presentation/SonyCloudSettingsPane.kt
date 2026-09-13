/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.ui.widget.other.QrCode
import com.RAZStudio.StudioRoom.core.ui.widget.other.QrCodeParams
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyCloudController

/**
 * Cloud settings for Sony Sync live upload, in two tabs:
 *  1. **Google Drive** — connect an account (paste a token), create a folder,
 *     make it public → link + QR.
 *  2. **Use existing folder** — paste a link to a folder you already created,
 *     Test it (is it a folder? read + write?), and use it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonyCloudSettingsPane(cloud: SonyCloudController, onExit: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                title = { Text("Cloud Settings · ${cloud.providerName}") },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Google Drive") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Use existing folder") })
            }
            when (tab) {
                0 -> CreateNewTab(cloud)
                else -> ExistingFolderTab(cloud)
            }
        }
    }
}

@Composable
private fun CreateNewTab(cloud: SonyCloudController) {
    val connected by cloud.connected.collectAsState()
    val account by cloud.account.collectAsState()
    val folderName by cloud.folderName.collectAsState()
    val shareLink by cloud.shareLink.collectAsState()
    val status by cloud.status.collectAsState()

    var token by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf("RAZStudio Uploads") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("1. Connect account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (connected) "Connected${if (account.isNotBlank()) " · $account" else ""}"
                    else "Paste a Google Drive access token to connect. Full sign-in comes later.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Access token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { cloud.connect(token) }, enabled = token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (connected) "Reconnect" else "Connect")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("2. Destination folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (folderName.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text("Current: $folderName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = newFolder, onValueChange = { newFolder = it }, label = { Text("New folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { cloud.createFolder(newFolder) }, enabled = connected, modifier = Modifier.fillMaxWidth()) { Text("Create folder") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("3. Share publicly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = cloud::makePublic, enabled = folderName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Make public & get link") }
                if (shareLink.isNotBlank()) {
                    Spacer(Modifier.height(12.dp)); Text(shareLink, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        QrCode(content = shareLink, modifier = Modifier.size(220.dp), qrParams = QrCodeParams())
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExistingFolderTab(cloud: SonyCloudController) {
    val connected by cloud.connected.collectAsState()
    val folderName by cloud.folderName.collectAsState()
    val shareLink by cloud.shareLink.collectAsState()
    val status by cloud.status.collectAsState()
    var link by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Use a folder you already made", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Paste the Google Drive folder link. Test checks it's a folder and that this " +
                        "account has read + write access. (Requires a token with full Drive scope, or " +
                        "the folder shared with the signed-in account — plain drive.file can't see " +
                        "folders made outside the app.)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    label = { Text("Folder link (…/drive/folders/…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { cloud.useExistingFolder(link) },
                    enabled = connected && link.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test & use folder") }
                if (!connected) {
                    Spacer(Modifier.height(6.dp))
                    Text("Connect an account on the Google Drive tab first.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (folderName.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Active folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(folderName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (shareLink.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            QrCode(content = shareLink, modifier = Modifier.size(200.dp), qrParams = QrCodeParams())
                        }
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
