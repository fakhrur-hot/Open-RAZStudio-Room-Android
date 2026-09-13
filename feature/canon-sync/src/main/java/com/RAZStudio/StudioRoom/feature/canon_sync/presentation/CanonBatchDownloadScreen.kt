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

package com.RAZStudio.StudioRoom.feature.canon_sync.presentation

import android.app.Activity
import android.net.Uri
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic.CanonBatchDownloadComponent

/**
 * Batch Download screen — pulls every photo from the camera into the
 * working directory, then keeps polling every 10 s for new shots
 * while the screen is open.
 *
 * Screen-protection per RAWEditor batch-processing precedent:
 *  - `FLAG_KEEP_SCREEN_ON` for the lifetime of the screen.
 *  - `PARTIAL_WAKE_LOCK` so background CPU work continues if the user
 *    locks the device (the wake lock allows the existing
 *    foreground-service notification to keep updating).
 *  - `setSustainedPerformanceMode(true)` so the OS caps peak clocks
 *    and prevents thermal-throttle-induced frame drops.
 *
 * Lifecycle:
 *  - Disconnects auto-exit via [BackHandler] + connection observer.
 *  - Back press → confirmation → calls `component.stop()` which fires
 *    `0x9118 EOS_CancelTransfer` for any in-flight handle.
 */
@Composable
fun CanonBatchDownloadContent(component: CanonBatchDownloadComponent) {
    val connection by component.connectionState.collectAsState()
    val batch by component.batchState.collectAsState()
    val settingsDefault by component.settingsDefaultFolder.collectAsState()
    var pickedFolder by remember { mutableStateOf<Uri?>(null) }
    var format by remember { mutableStateOf(CanonSyncRepository.BatchFormat.BOTH) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // ── Screen protection ────────────────────────────────────────────
    // Mirror of RawV3PerfHints.BatchPerfScope's three layers, inlined
    // here so we don't grow a cross-feature dependency.
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RAZStudio:CanonBatchDownload",
        )
        runCatching { wakeLock?.acquire(MAX_BATCH_DURATION_MS) }
        runCatching {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.setSustainedPerformanceMode(true)
        }
        onDispose {
            runCatching {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window?.setSustainedPerformanceMode(false)
            }
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    // ── Auto-exit on session loss ────────────────────────────────────
    LaunchedEffect(connection) {
        if (connection != ConnectionState.Connected) {
            component.stop()
            component.onGoBack()
        }
    }

    // ── SAF folder picker for "+ New Folder" ─────────────────────────
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            pickedFolder = uri
        }
    }

    // ── Back-confirmation ────────────────────────────────────────────
    BackHandler { showExitConfirm = true }
    if (showExitConfirm) {
        ExitConfirmDialog(
            running = batch is CanonSyncRepository.CanonBatchPhase.Running ||
                batch is CanonSyncRepository.CanonBatchPhase.Scanning ||
                batch is CanonSyncRepository.CanonBatchPhase.Watching,
            onConfirm = {
                showExitConfirm = false
                component.stop()
                component.onGoBack()
            },
            onDismiss = { showExitConfirm = false },
        )
    }

    val effectiveFolder = pickedFolder ?: settingsDefault

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(onBack = { showExitConfirm = true })
            if (effectiveFolder == null) {
                FolderPrompt(
                    onPickFolder = { folderPicker.launch(null) },
                    onUseSettings = { pickedFolder = settingsDefault },
                    hasSettingsDefault = settingsDefault != null,
                )
            } else {
                BatchContent(
                    folderUri = effectiveFolder,
                    format = format,
                    onFormatChange = { format = it },
                    batch = batch,
                    component = component,
                    onStart = { component.start(effectiveFolder, format) },
                    onStartProcess = { ctx, processExisting ->
                        component.startDownloadAndProcess(
                            effectiveFolder, format, ctx, processExisting,
                        )
                    },
                    onStop = component::stop,
                    onChangeFolder = { folderPicker.launch(null) },
                )
            }
        }
    }

    // ── Batch screensaver overlay ────────────────────────────────────
    // Mirrors RAWEditor: when a Canon-Sync batch (download or
    // download+process) is active and the user idles for 5 s, dim the
    // window, hide system bars, draw the bouncing progress puck and
    // optionally play the protection music. Tap to dismiss.
    val processing by component.processingState.collectAsState()
    val isCanonBatchRunning = batch is CanonSyncRepository.CanonBatchPhase.Running ||
        batch is CanonSyncRepository.CanonBatchPhase.Scanning ||
        batch is CanonSyncRepository.CanonBatchPhase.Watching ||
        processing is com.RAZStudio.StudioRoom.feature.canon_sync.domain
            .DownloadAndProcessCoordinator.ProcessPhase.Running
    val protectionPrefs = remember(context) {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .BatchProtectionPrefs(context)
    }
    val audioPlayer = remember(context) {
        com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .BatchAudioPlayer(context)
    }
    var screensaverVisible by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isCanonBatchRunning, protectionPrefs.screenProtectionEnabled) {
        if (!isCanonBatchRunning || !protectionPrefs.screenProtectionEnabled) {
            screensaverVisible = false
            audioPlayer.stop()
            return@LaunchedEffect
        }
        lastInteractionMs = System.currentTimeMillis()
        while (isCanonBatchRunning) {
            kotlinx.coroutines.delay(500L)
            val idleMs = System.currentTimeMillis() - lastInteractionMs
            if (idleMs >= 5_000L && !screensaverVisible) {
                screensaverVisible = true
                if (protectionPrefs.musicEnabled) {
                    audioPlayer.start(protectionPrefs.volumeLevel.gain)
                }
            }
        }
        screensaverVisible = false
        audioPlayer.stop()
    }
    DisposableEffect(Unit) {
        onDispose { audioPlayer.stop() }
    }
    if (screensaverVisible) {
        // Best-available progress numbers: prefer the live download
        // counter, fall back to the processor counter when only
        // processing is active.
        val (idx, total) = when (val b = batch) {
            is CanonSyncRepository.CanonBatchPhase.Running ->
                (b.doneCount + 1) to b.totalCount
            else -> when (val p = processing) {
                is com.RAZStudio.StudioRoom.feature.canon_sync.domain
                    .DownloadAndProcessCoordinator.ProcessPhase.Running ->
                    (p.doneCount + 1) to (p.doneCount + p.failCount + 1)
                else -> 0 to 0
            }
        }
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation
            .raw.components.BatchScreensaverOverlay(
                windowBrightness = protectionPrefs.brightnessLevel.windowBrightness,
                currentIndex = idx,
                totalCount = total,
                onDismiss = {
                    screensaverVisible = false
                    lastInteractionMs = System.currentTimeMillis()
                    audioPlayer.stop()
                },
            )
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.clickable(onClick = onBack),
        ) {
            Text(
                "‹ Back",
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Batch Download",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FolderPrompt(
    onPickFolder: () -> Unit,
    onUseSettings: () -> Unit,
    hasSettingsDefault: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Where should photos land?",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a dedicated folder for this batch or reuse the app's " +
                "Default Output folder from Settings.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.clickable(onClick = onPickFolder),
        ) {
            Text(
                "+ New Folder",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (hasSettingsDefault) {
            TextButton(onClick = onUseSettings) {
                Text("Use Settings default folder")
            }
        } else {
            Text(
                "(Set a Default Output folder in app Settings to enable the " +
                    "shortcut here.)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BatchContent(
    folderUri: Uri,
    format: CanonSyncRepository.BatchFormat,
    onFormatChange: (CanonSyncRepository.BatchFormat) -> Unit,
    batch: CanonSyncRepository.CanonBatchPhase,
    component: CanonBatchDownloadComponent,
    onStart: () -> Unit,
    onStartProcess: (com.RAZStudio.StudioRoom.feature.photo_editor.raw
        .RawBatchProcessor.PerFileContext, Boolean) -> Unit,
    onStop: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Folder summary row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Saving to",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(prettySafPath(folderUri), fontSize = 13.sp)
            }
            TextButton(onClick = onChangeFolder) { Text("Change") }
        }
        Spacer(Modifier.height(8.dp))
        // Format chip row.
        Text(
            "Format",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatChip("RAW + JPG", format == CanonSyncRepository.BatchFormat.BOTH) {
                onFormatChange(CanonSyncRepository.BatchFormat.BOTH)
            }
            FormatChip("RAW only", format == CanonSyncRepository.BatchFormat.RAW) {
                onFormatChange(CanonSyncRepository.BatchFormat.RAW)
            }
            FormatChip("JPEG only", format == CanonSyncRepository.BatchFormat.JPEG) {
                onFormatChange(CanonSyncRepository.BatchFormat.JPEG)
            }
        }
        Spacer(Modifier.height(16.dp))
        // Status panel.
        BatchStatusPanel(batch)
        // Processing status — only meaningful when D&P is running;
        // hidden otherwise so the screen stays calm for plain downloads.
        ProcessingStatusPanel(component)
        Spacer(Modifier.height(16.dp))
        // ── Batch Processing settings panel ────────────────────────
        // Checkbox-controlled, default off. When off, the Download&Process
        // button is hidden; only plain "Start batch download" runs.
        // When on, expands to show the shared RawBatchSettingsPanel —
        // SAME widget set as RAWEditor's batch processing menu (preset,
        // scale mode, EXIF policy, ICC profile, format, dimensions).
        // State persists across screens via RawBatchPrefs.
        val ctx = LocalContext.current
        var processingEnabled by remember { mutableStateOf(false) }
        // When true, every RAW (or JPG in JPEG-only) already in the
        // download folder is enqueued for processing on Start. Useful
        // for the common case where the user already downloaded the
        // shoot and just wants to process it now — without this, the
        // skip-existing filter would leave the pipeline idle.
        var processExisting by remember { mutableStateOf(true) }
        val settings = com.RAZStudio.StudioRoom.feature.photo_editor.presentation
            .raw.components.rememberRawBatchSettings()
        val presets = remember {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw
                .RawPresetsStorage.loadIndex(ctx)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { processingEnabled = !processingEnabled },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = processingEnabled,
                        onCheckedChange = { processingEnabled = it },
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Batch Processing",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (processingEnabled)
                                "Process each photo as it arrives, using the settings below"
                            else "Off — downloads only, no processing",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (processingEnabled) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    com.RAZStudio.StudioRoom.feature.photo_editor.presentation
                        .raw.components.RawBatchSettingsPanel(
                            settings = settings,
                            presets  = presets,
                            enabled  = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { processExisting = !processExisting },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = processExisting,
                            onCheckedChange = { processExisting = it },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Also process existing files in folder",
                                fontSize = 13.sp,
                            )
                            Text(
                                if (processExisting)
                                    "RAWs already in the download folder will be re-processed too"
                                else "Only photos downloaded during this session will be processed",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Processed output saves to the same folder as the " +
                            "downloads above.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val running = batch is CanonSyncRepository.CanonBatchPhase.Running ||
            batch is CanonSyncRepository.CanonBatchPhase.Scanning ||
            batch is CanonSyncRepository.CanonBatchPhase.Watching
        if (processingEnabled && !running) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val perFileContext = buildPerFileContext(
                            ctx, settings, component.rawBatchProcessor,
                            outputFolderUri = folderUri,
                        )
                        onStartProcess(perFileContext, processExisting)
                    },
            ) {
                Text(
                    "Download & Process",
                    color = MaterialTheme.colorScheme.onTertiary,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = if (running) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = if (running) onStop else onStart),
        ) {
            Text(
                if (running) "Stop batch" else "Start batch download",
                color = if (running) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun FormatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BatchStatusPanel(batch: CanonSyncRepository.CanonBatchPhase) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (batch) {
                is CanonSyncRepository.CanonBatchPhase.Idle -> {
                    Text("Ready", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Tap below to scan the camera card and pull every " +
                            "matching photo. The screen stays on; the app " +
                            "checks for new shots every 10 seconds.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is CanonSyncRepository.CanonBatchPhase.Scanning -> {
                    Text("Scanning…", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is CanonSyncRepository.CanonBatchPhase.Running -> {
                    val left = (batch.totalCount - batch.doneCount - batch.skippedCount)
                        .coerceAtLeast(0)
                    Text(
                        "$left photo${if (left == 1) "" else "s"} left",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${batch.doneCount}/${batch.totalCount} done · " +
                            "${batch.skippedCount} skipped (already on phone)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(batch.currentFilename, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    val pct = if (batch.currentBytesTotal > 0) {
                        (batch.currentBytes.toFloat() / batch.currentBytesTotal).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is CanonSyncRepository.CanonBatchPhase.Watching -> {
                    Text(
                        "Watching for new shots…",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${batch.doneCount} downloaded · " +
                            "${batch.skippedCount} skipped. " +
                            "Checking every 10 seconds.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is CanonSyncRepository.CanonBatchPhase.Error -> {
                    Text(
                        "Error",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(batch.message, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ExitConfirmDialog(
    running: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (running) "Stop batch download?" else "Exit batch download?") },
        text = {
            Text(
                if (running) {
                    "Cancels the in-flight transfer and stops watching for " +
                        "new shots. Photos already on the phone stay; the " +
                        "camera card is unchanged."
                } else "Returns to Canon Sync."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (running) "Stop" else "Exit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay") }
        },
    )
}

private fun prettySafPath(uri: Uri): String {
    val last = uri.lastPathSegment ?: return uri.toString()
    val decoded = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }
        .getOrNull() ?: return uri.toString()
    val colon = decoded.indexOf(':')
    val path = if (colon >= 0) decoded.substring(colon + 1) else decoded
    return path.ifBlank { "(root)" }
}

/**
 * Hard ceiling for the wake-lock acquired on the batch screen. Matches
 * the dataSync foreground-service budget (6 h) so we never out-live the
 * platform-allowed background window. Re-acquired implicitly on
 * Composable re-entry; not a problem in practice because the screen
 * survives the whole batch.
 */
private const val MAX_BATCH_DURATION_MS: Long = 6L * 60L * 60L * 1000L

// ─────────────────────────── Processing status ───────────────────────

/**
 * Surfaces the processing pipeline's current state alongside the
 * download status — so the user sees "downloading IMG_4127.CR2"
 * AND "processing IMG_4126.CR2" simultaneously when the read-ahead
 * pipeline is fully loaded.
 *
 * Hidden when processing is idle to keep the screen calm for
 * non-processing batches.
 */
@Composable
private fun ProcessingStatusPanel(component: CanonBatchDownloadComponent) {
    val state by component.processingState.collectAsState()
    val s = state
    if (s is com.RAZStudio.StudioRoom.feature.canon_sync.domain
        .DownloadAndProcessCoordinator.ProcessPhase.Idle) {
        return
    }
    Spacer(Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (s) {
                is com.RAZStudio.StudioRoom.feature.canon_sync.domain
                    .DownloadAndProcessCoordinator.ProcessPhase.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (s.currentFilename.isBlank()) "Processing…"
                            else "Processing ${s.currentFilename}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${s.doneCount} done · ${s.failCount} failed",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                            .copy(alpha = 0.7f),
                    )
                }
                is com.RAZStudio.StudioRoom.feature.canon_sync.domain
                    .DownloadAndProcessCoordinator.ProcessPhase.Done ->
                    Text(
                        "Processing finished: ${s.doneCount} done, ${s.failCount} failed",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                is com.RAZStudio.StudioRoom.feature.canon_sync.domain
                    .DownloadAndProcessCoordinator.ProcessPhase.Error ->
                    Text(
                        "Processing error: ${s.message}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                else -> Unit
            }
        }
    }
}

/**
 * Build the [RawBatchProcessor.PerFileContext] from the shared
 * [com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawBatchSettings]
 * state holder. Threads every panel widget through to the v3
 * coordinator: preset (action stack OR Auto-Expo mode + NR level),
 * scale mode + resize sharpen, EXIF policy, ICC profile flag, format,
 * and dimensions.
 */
private fun buildPerFileContext(
    context: android.content.Context,
    settings: com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw
        .components.RawBatchSettings,
    processor: com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor,
    /**
     * Where processed outputs should land. Without this, the v3 coordinator
     * falls back to the global Settings save location and processed files
     * end up far from the original downloads — confusing the user. Threading
     * the Canon Sync working folder through `oneTimeSaveLocationUri` keeps
     * "original + processed" co-located, as the panel hint promises.
     */
    outputFolderUri: Uri,
): com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.PerFileContext {
    // Load preset action stack (falls back to empty — no live stack in Canon Sync).
    val presetActions: List<com.RAZStudio.StudioRoom.feature.photo_editor
        .raw.model.RawAction> =
        settings.selectedPresetIndex?.let {
            com.RAZStudio.StudioRoom.feature.photo_editor.raw
                .RawPresetsStorage.loadPreset(context, it)
        } ?: emptyList()
    return processor.prepareContext(
        actions = presetActions,
        autoExposure = settings.autoExposure,
        lensCorrection = settings.lensCorrection,
        aiReconstruct = settings.aiReconstruct,
        aiEnhance = settings.aiEnhance,
        guidedFilter = settings.guidedFilter,
        useCameraColorProfile = settings.useCameraColorProfile,
        outputUri = outputFolderUri.toString(),
    )
}
