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

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.RAZStudio.StudioRoom.core.resources.icons.FolderImage
import com.RAZStudio.StudioRoom.core.resources.icons.FolderOpen
import com.RAZStudio.StudioRoom.core.ui.utils.content_pickers.rememberFolderPicker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.Add
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.core.resources.icons.CameraAlt
import com.RAZStudio.StudioRoom.core.resources.icons.DownloadForOffline
import com.RAZStudio.StudioRoom.core.resources.icons.Check
import com.RAZStudio.StudioRoom.core.resources.icons.Settings
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic.CanonSyncComponent
import com.RAZStudio.StudioRoom.feature.canon_sync.service.BatteryOptimizationHelper
import com.RAZStudio.StudioRoom.feature.canon_sync.service.OemKeepAliveGuide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanonSyncContent(component: CanonSyncComponent) {
    val workingDirUri by component.workingDirUri.collectAsState()
    val folders by component.folders.collectAsState()
    val armedUri by component.armedFolderUri.collectAsState()
    val connection by component.connectionState.collectAsState()
    val formatMode by component.formatMode.collectAsState()
    val sortMode by component.sortMode.collectAsState()
    val lastError by component.lastError.collectAsState()
    val phoneWifi by component.phoneWifiState.collectAsState()
    val availableCameras by component.availableCameras.collectAsState()
    val isScanning by component.isScanning.collectAsState()
    // Drive the scan loop on/off based on phone Wi-Fi state +
    // connection state. Start when the phone is hotspot/no-internet
    // (i.e. potentially camera-reachable) AND we're not already
    // Connected. Stop on Connected or when phone goes Off/internet
    // (no value scanning there — no Canon device can be on it).
    androidx.compose.runtime.LaunchedEffect(phoneWifi, connection) {
        // Delay the first scanner tick by 1.5s after mount so the
        // Canon Sync screen has time to finish drawing its first
        // composition before any background scan competes with
        // CPU/wakelock setup. Without this, the screen opens visibly
        // sluggish (multi-second Davey frames) on cold start because
        // the scan loop's coroutines + log emission start the moment
        // the LaunchedEffect runs.
        kotlinx.coroutines.delay(1_500L)
        // Camera-routable states: phone hosting a hotspot, phone on a
        // no-internet Wi-Fi (camera AP), OR phone on a regular home Wi-Fi WITH
        // internet — the "both phone and camera on home Wi-Fi" topology, where
        // a Canon body joined the same router in EOS Utility mode. We used to
        // exclude ConnectedToInternet ("no Canon can be there"), which was
        // wrong and blocked the home-Wi-Fi path entirely: the subnet discovery
        // scans whatever subnet the phone is on, so a camera on the home router
        // is reachable just the same.
        val phoneOk = phoneWifi == PhoneWifiState.HotspotActive ||
            phoneWifi == PhoneWifiState.ConnectedNoInternet ||
            phoneWifi == PhoneWifiState.ConnectedToInternet
        // Keep scanning while phone is camera-routable AND not yet
        // Connected. Crucially: do NOT stop during Connecting — that
        // state fires repeatedly during auto-reconnect attempts and
        // stopping the scan means the camera's new DHCP address never
        // gets discovered while the retry loop is running.
        if (phoneOk && connection != ConnectionState.Connected) component.startCameraScanLoop()
        else if (!phoneOk || connection == ConnectionState.Connected) component.stopCameraScanLoop()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { component.stopCameraScanLoop() }
    }
    val budget by component.dataSyncBudget.collectAsState()
    val cameraProfiles by component.cameraProfiles.collectAsState()
    var profilePendingDelete by remember {
        mutableStateOf<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile?>(null)
    }

    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showNewFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showCameraBrowser by rememberSaveable { mutableStateOf(false) }

    // When the user taps Download/Edit without a working directory
    // set, we open the SAF folder picker; the picker's onSuccess
    // both persists the choice as the new working dir AND runs the
    // deferred action with the freshly-picked Uri passed through
    // (so we don't have to wait for the StateFlow to propagate).
    var pendingAfterFolderPick by remember {
        mutableStateOf<((android.net.Uri) -> Unit)?>(null)
    }
    val folderPickerScope = androidx.compose.runtime.rememberCoroutineScope()
    val folderPickerForDownload = com.RAZStudio.StudioRoom.core.ui.utils.content_pickers
        .rememberFolderPicker(onSuccess = { uri ->
            component.setWorkingDir(uri)
            pendingAfterFolderPick?.invoke(uri)
            pendingAfterFolderPick = null
        })

    // Hold FLAG_KEEP_SCREEN_ON while BOTH pips are green — i.e. phone is on
    // a Wi-Fi (camera AP or other) AND the PTP/IP session is live. Without
    // this Android sleeps the screen after the user's normal idle timeout,
    // which immediately kills the Wi-Fi radio's active mode → camera AP
    // drops → session dies. Same pattern as RAW Editor's
    // BatchScreensaverOverlay: a DisposableEffect that ORs the flag into
    // the activity window's attributes and removes it on key change /
    // dispose so the rest of the app keeps normal sleep behaviour.
    val screenOnActive = (
        phoneWifi == PhoneWifiState.ConnectedNoInternet ||
            phoneWifi == PhoneWifiState.ConnectedToInternet ||
            phoneWifi == PhoneWifiState.HotspotActive
        ) && connection == ConnectionState.Connected
    val context = LocalContext.current
    DisposableEffect(screenOnActive) {
        val window = (context as? android.app.Activity)?.window
        if (screenOnActive && window != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── Permission flow (changed 2026-06-04) ──────────────────────────────
    //   Previously: tapping Connect launched the permission request, then
    //   on grant called component.connect(). That made the first-time
    //   connect tap feel laggy and surfaced a runtime prompt RIGHT when
    //   the user expected an immediate scan/connect attempt.
    //
    //   Now: permissions are requested ONCE on screen entry (the moment
    //   the user opens the Canon Sync feature). When Connect is later
    //   tapped, permissions are either already granted (silent connect)
    //   or we re-prompt as a fallback. The on-entry pump runs only the
    //   first time the screen lands; subsequent re-entries skip it if
    //   the prerequisite permission is already held.
    var initialPermissionPumpDone by rememberSaveable { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }
    // Tracks whether the most recent permission launch was triggered by an
    // explicit Connect tap (true) or the on-entry pre-prompt (false). Reset
    // by the result callback after dispatching.
    val pendingConnectAfterPermission = remember { mutableStateOf(false) }
    // IP of the specific camera card the user tapped, so connect targets THAT
    // camera (not the generic default). Null = generic connect.
    val pendingConnectIp = remember { mutableStateOf<String?>(null) }

    fun hasWifiPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, needed,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // The Wi-Fi peer-discovery permission is the only hard prerequisite —
        // without it requestNetwork() throws SecurityException. POST_NOTIFICATIONS
        // is best-effort: the foreground service still runs without it, just
        // without a visible notification, so we proceed to connect regardless.
        val wifiPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionsGranted = results[wifiPermission] == true
        // Only auto-connect when this dialog was opened by an explicit
        // Connect tap — not by the on-entry pump (which fires before the
        // user has chosen a camera). The dispatcher below tracks that
        // via `pendingConnectAfterPermission`.
        if (permissionsGranted && pendingConnectAfterPermission.value) {
            pendingConnectAfterPermission.value = false
            val ip = pendingConnectIp.value
            pendingConnectIp.value = null
            if (ip != null) component.connect(cameraIp = ip) else component.connect()
        }
    }

    // On-entry permission pump — fires once per screen mount. If the user
    // already granted permissions previously, we skip the prompt and just
    // record granted=true. Otherwise the system dialog appears right when
    // they enter Canon Sync, before they tap anything.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!initialPermissionPumpDone) {
            initialPermissionPumpDone = true
            if (hasWifiPermission()) {
                permissionsGranted = true
            } else {
                pendingConnectAfterPermission.value = false
                permissionLauncher.launch(requiredPermissions())
            }
        } else {
            permissionsGranted = hasWifiPermission()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Canon Sync") },
                navigationIcon = {
                    IconButton(onClick = component.onGoBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusBar(
                phoneState = phoneWifi,
                connectionState = connection,
                lastError = lastError,
                hasDetectedCameras = availableCameras.isNotEmpty(),
                isScanning = isScanning,
                onSettingsClick = { showSettingsDialog = true },
                onConnectClick = {
                    if (hasWifiPermission()) {
                        // Fast path — permissions already granted on screen
                        // entry; go straight to connect with no UI lag.
                        component.connect()
                    } else {
                        // Fallback — user denied on entry; re-prompt now
                        // and auto-connect on grant.
                        pendingConnectAfterPermission.value = true
                        permissionLauncher.launch(requiredPermissions())
                    }
                },
                onDisconnectClick = component::disconnect,
                onScanClick = component::scanForCanonNow,
            )

            // ── Detected-cameras list (always shown while scanner is
            //    alive). One card per camera; tap to connect. The
            //    Connected one keeps a green tick and is non-tappable.
            if (availableCameras.isNotEmpty() &&
                (connection != ConnectionState.Connected ||
                    availableCameras.size > 1)
            ) {
                DetectedCamerasList(
                    cameras = availableCameras,
                    isConnected = connection == ConnectionState.Connected,
                    isConnecting = connection == ConnectionState.Connecting,
                    onTapCamera = { cam ->
                        // Target the camera whose card was tapped (its IP),
                        // rather than the generic default. Permissions are
                        // requested on screen entry — tap is silent on the
                        // happy path.
                        if (hasWifiPermission()) {
                            component.connect(cameraIp = cam.ip)
                        } else {
                            pendingConnectIp.value = cam.ip
                            pendingConnectAfterPermission.value = true
                            permissionLauncher.launch(requiredPermissions())
                        }
                    },
                )
            }

            // Quick-Connect: tap a remembered camera to skip discovery
            // (cached IP + host GUID → straight to InitCommand). Long-
            // press shows a confirmation dialog before forgetting.
            //
            // Shown also during Connecting so the user can long-press
            // to delete a stale profile that's causing the hang. Tiles
            // are non-tappable while connecting (the tap callback
            // becomes a no-op) — Stop button below handles cancellation.
            if (cameraProfiles.isNotEmpty() &&
                connection != ConnectionState.Connected
            ) {
                QuickConnectRow(
                    profiles = cameraProfiles,
                    onTap = { profile ->
                        if (connection != ConnectionState.Connecting) {
                            component.connectToProfile(profile)
                        }
                    },
                    onLongPress = { profile -> profilePendingDelete = profile },
                )
                // Stop button — surfaces only while a connect attempt is
                // in flight. Tapping it cancels the 3-retry loop and
                // returns to Disconnected so the user can pick a
                // different profile or delete the bad one without
                // waiting ~21 s for the timeouts to drain.
                if (connection == ConnectionState.Connecting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = component::cancelConnect) {
                            Text("Stop")
                        }
                    }
                }
            }

            DataSyncBudgetBanner(budget = budget)

            // ── Section 1: Working directory ──────────────────────────────
            // Groups "+ New Folder" (top) + the current path (below) under
            // a single section header. New Folder lives ABOVE the path so
            // the action sits where the eye lands first; the read-only
            // path display reads as confirmation underneath.
            if (workingDirUri == null) {
                EmptyWorkingDirPicker(onPick = component::setWorkingDir)
            } else {
                WorkingDirSection(
                    workingDirUri = workingDirUri,
                    newFolderEnabled = connection == ConnectionState.Connected,
                    onNewFolderClick = { showNewFolderDialog = true },
                )
            }

            // ── Section 2: Camera actions ────────────────────────────────
            // 3-card grid visible only while the PTP session is alive.
            // When the session drops (heartbeat dead, auto-reconnect
            // exhausted, or user-initiated Disconnect), the
            // LaunchedEffect below closes any open browser dialog /
            // preview canvas so the user can't poke at stale state.
            if (connection == ConnectionState.Connected && workingDirUri != null) {
                CameraActionsSection(
                    onBrowsePhotos = {
                        showCameraBrowser = true
                        component.refreshCameraObjects()
                    },
                    onLiveRemoteShoot = {
                        component.onNavigate(
                            com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.CanonRemoteShoot
                        )
                    },
                    onBatchDownload = {
                        component.onNavigate(
                            com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen.CanonBatchDownload
                        )
                    },
                )

                if (armedUri == null && folders.isNotEmpty()) {
                    Text(
                        text = "Long-press a folder to capture shots into it.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                } else if (armedUri != null) {
                    Text(
                        text = "Armed: every new shot will be saved on the SD card and " +
                            "auto-downloaded here. Long-press again to disarm.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }

                FolderGrid(
                    folders = folders,
                    armedUri = armedUri,
                    onTap = { tile -> component.openFolderInGallery(tile.uri) },
                    onLongPress = { tile ->
                        if (tile.uri == armedUri) component.disarmFolder()
                        else component.armFolder(tile.uri)
                    },
                )
            }
        }
    }

    // When the PTP session drops, immediately close any browser
    // dialog or preview canvas this screen owns. The Live Remote
    // Shooting screen handles its own pop-back via its component's
    // LaunchedEffect — see CanonRemoteShootContent. We don't need to
    // dispatch a navigateBack here for that screen; it self-exits.
    androidx.compose.runtime.LaunchedEffect(connection) {
        if (connection != ConnectionState.Connected) {
            showCameraBrowser = false
            component.dismissPreview()
        }
    }

    if (showSettingsDialog) {
        ConnectionSettingsDialog(
            formatMode = formatMode,
            onFormatModeChange = component::setFormatMode,
            onDismiss = { showSettingsDialog = false },
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                component.createSubfolder(name)
                showNewFolderDialog = false
            },
        )
    }

    if (showCameraBrowser) {
        // The browser filter chips were removed — the user now always
        // sees RAW + JPEG together. Force the underlying formatMode on
        // dialog mount so a stale "RAW only" / "JPEG only" persisted
        // value from before the chips disappeared can't hide files
        // from the listing. Only re-fires when the dialog re-opens.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (formatMode != FormatMode.RAW_AND_JPEG) {
                component.setFormatMode(FormatMode.RAW_AND_JPEG)
            }
        }
        val cameraPhotos by component.cameraPhotos.collectAsState()
        val cameraBusy by component.cameraBrowserBusy.collectAsState()
        val gridThumbs by component.gridThumbs.collectAsState()
        // Kick the grid-thumb fetch batch whenever the underlying photo
        // SET changes — same handles ⇒ no work (repository short-circuits).
        // Crucially we do NOT cancel the in-flight job on dismiss any more:
        // the user spec is that thumbs survive dialog close and are only
        // evicted when the PTP session ends. Cancelling on every dismiss
        // wasted bandwidth re-fetching the same thumbs each time the user
        // re-opened the browser.
        androidx.compose.runtime.LaunchedEffect(cameraPhotos) {
            if (cameraPhotos.isNotEmpty()) component.startGridThumbsFetch(cameraPhotos)
        }
        CameraBrowserDialog(
            photos = cameraPhotos,
            thumbs = gridThumbs,
            onTilePreview = component::previewCameraObject,
            busy = cameraBusy,
            onRefresh = component::refreshCameraObjects,
            formatMode = formatMode,
            onFormatModeChange = component::setFormatMode,
            onVisibleHandlesChanged = { handles ->
                component.updateVisibleGridHandles(handles, cameraPhotos)
            },
            sortMode = sortMode,
            onSortModeChange = component::setSortMode,
            onDismiss = { showCameraBrowser = false },
        )
    }

    // Photo preview canvas, shown when the user taps a tile. The
    // canvas overlays the full screen with the photo + Edit/Download
    // buttons. State is driven by [component.previewState], which
    // flips through Idle → Loading → Ready as the bytes stream in.
    val previewState by component.previewState.collectAsState()
    // Ordered photo list (already filtered by formatMode) that backs
    // the swipe pager — same list the grid renders.
    val orderedPhotos by component.cameraPhotos.collectAsState()
    when (val ps = previewState) {
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository.PreviewState.Idle -> Unit
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository.PreviewState.Loading ->
            PhotoPreviewLoading(
                filename = ps.filename,
                onDismiss = component::dismissPreview,
            )
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository.PreviewState.Ready -> {
            // Resolve which CameraPhoto this preview belongs to so the
            // canvas can prefer the RAW sibling for Edit / Download
            // when available.
            val photo = remember(ps.handle) { component.findPhotoByHandle(ps.handle) }
            // Index of the currently-displayed photo inside the filtered
            // list — drives the swipe pager. Recomputed on photo / list
            // change. -1 when the list has shifted under us (e.g. a
            // Refresh between open and now); we just disable swipe in
            // that edge case.
            val currentIndex = remember(orderedPhotos, photo) {
                if (photo == null) -1 else orderedPhotos.indexOf(photo)
            }
            // Kick off neighbor preload whenever the user lands on a
            // new index — keeps ±2 warm so swipes are instant.
            androidx.compose.runtime.LaunchedEffect(currentIndex, orderedPhotos) {
                if (currentIndex >= 0) {
                    component.prefetchPreviewNeighbors(orderedPhotos, currentIndex)
                }
            }
            val context = LocalContext.current
            // Local progress state — the canvas blocks Edit/Download
            // taps until the full chunked transfer completes so the
            // user sees a spinner instead of an empty close.
            var downloadInProgress by remember { mutableStateOf(false) }
            var downloadLabel by remember { mutableStateOf("") }
            val workingDirSet by component.workingDirUri
                .collectAsState(initial = null)
            // The persisted SAF grant can be revoked out from under us
            // (app reinstall, user clears the storage permission, the
            // folder gets deleted). When that happens, the URI is still
            // saved in DataStore but `canWrite()` is false — the
            // download will fail with "no destination folder". Validate
            // here so we can re-launch the folder picker instead of
            // silently failing.
            val workingDirWritable = remember(workingDirSet) {
                val u = workingDirSet ?: return@remember false
                runCatching {
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(context, u)
                        ?.let { it.isDirectory && it.canWrite() } == true
                }.getOrDefault(false)
            }
            val mainScope = androidx.compose.runtime.rememberCoroutineScope()
            // `downloadCameraPhoto`'s onComplete fires from the repo's
            // IO coroutine — touching Toast/UI state from there throws
            // "Can't toast on a thread that has not called Looper.prepare()".
            // Hop to the Main dispatcher before doing anything user-facing.
            val onMain: (suspend () -> Unit) -> Unit = { block ->
                mainScope.launch(kotlinx.coroutines.Dispatchers.Main) { block() }
            }
            PhotoPreviewCanvas(
                state = ps,
                photo = photo,
                inProgress = downloadInProgress,
                progressLabel = downloadLabel,
                orderedPhotos = orderedPhotos,
                currentIndex = currentIndex,
                onSwipeToIndex = { idx ->
                    orderedPhotos.getOrNull(idx)?.let(component::previewCameraObject)
                },
                onDismiss = component::dismissPreview,
                onEdit = onEdit@{
                    val p = photo ?: return@onEdit
                    // Honor the user's format filter: JPEG-only pulls
                    // the JPG sibling (faster, no LibRaw needed); RAW
                    // and RAW+JPEG prefer the CR2/CR3 so the editor can
                    // do its full 16-bit pipeline. Fall back to whatever
                    // handle exists if the preferred one is missing.
                    val preferJpeg = formatMode == FormatMode.JPEG
                    val handle = if (preferJpeg) {
                        p.jpegHandle ?: p.rawHandle ?: return@onEdit
                    } else {
                        p.rawHandle ?: p.jpegHandle ?: return@onEdit
                    }
                    val isJpeg = handle == p.jpegHandle && p.jpegHandle != null
                    val ext = if (isJpeg) "JPG" else "CR2"
                    // Shared body — runs the actual download. Closes
                    // over [explicitFolder] so the path works both
                    // when workingDir is already set AND when we just
                    // got it from the SAF picker.
                    val runEdit: suspend (android.net.Uri?) -> Unit = { folder ->
                        downloadLabel = "Downloading ${p.displayName}.$ext for editing…"
                        downloadInProgress = true
                        component.editCameraPhoto(p, handle, ext, explicitFolder = folder) { uri ->
                            onMain {
                                downloadInProgress = false
                                if (uri == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Edit-prep failed for ${p.displayName}.$ext",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    component.dismissPreview()
                                }
                            }
                        }
                    }
                    if (!workingDirWritable) {
                        // Close canvas + ask the OS folder picker.
                        // After the user picks, the picker callback
                        // invokes pendingAfterFolderPick with the
                        // chosen Uri so we don't have to wait for the
                        // settings StateFlow to propagate.
                        pendingAfterFolderPick = { picked ->
                            folderPickerScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                runEdit(picked)
                            }
                        }
                        component.dismissPreview()
                        folderPickerForDownload.launch()
                    } else {
                        folderPickerScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            runEdit(null)
                        }
                    }
                },
                onDownload = onDl@{
                    val p = photo ?: return@onDl
                    val preferJpeg = formatMode == FormatMode.JPEG
                    val handle = if (preferJpeg) {
                        p.jpegHandle ?: p.rawHandle ?: return@onDl
                    } else {
                        p.rawHandle ?: p.jpegHandle ?: return@onDl
                    }
                    val isJpeg = handle == p.jpegHandle && p.jpegHandle != null
                    val ext = if (isJpeg) "JPG" else "CR2"
                    val runDownload: suspend (android.net.Uri?) -> Unit = { folder ->
                        downloadLabel = "Downloading ${p.displayName}.$ext…"
                        downloadInProgress = true
                        component.downloadCameraPhoto(p, handle, ext, explicitFolder = folder) { uri ->
                            onMain {
                                downloadInProgress = false
                                android.widget.Toast.makeText(
                                    context,
                                    if (uri != null) "Saved ${p.displayName}.$ext"
                                    else "Download failed for ${p.displayName}.$ext",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                if (uri != null) component.dismissPreview()
                            }
                        }
                    }
                    if (!workingDirWritable) {
                        pendingAfterFolderPick = { picked ->
                            folderPickerScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                runDownload(picked)
                            }
                        }
                        component.dismissPreview()
                        folderPickerForDownload.launch()
                    } else {
                        folderPickerScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            runDownload(null)
                        }
                    }
                },
            )
        }
    }

    val pending = profilePendingDelete
    if (pending != null) {
        CameraProfileEditDialog(
            profile = pending,
            onDismiss = { profilePendingDelete = null },
            onRename = { newAlias ->
                component.renameProfile(pending.cameraMac, newAlias)
                profilePendingDelete = null
            },
            onForget = {
                component.forgetProfile(pending.cameraMac)
                profilePendingDelete = null
            },
        )
    }

}

/**
 * Horizontal-scrolling list of saved camera profiles. Each tile shows
 * the friendly name + last-known IP + a "last seen" timestamp; tap to
 * connect, long-press to delete (with confirmation dialog driven by
 * the parent).
 */
@Composable
private fun QuickConnectRow(
    profiles: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile>,
    onTap: (com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile) -> Unit,
    onLongPress: (com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Quick connect",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles, key = { it.cameraMac }) { profile ->
                QuickConnectTile(
                    profile = profile,
                    onTap = { onTap(profile) },
                    onLongPress = { onLongPress(profile) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QuickConnectTile(
    profile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Top line: user alias if set, otherwise the body's
            // friendly name (or model+serial fallback). IP / MAC are
            // intentionally absent — they aren't useful at a glance
            // and the IP changes per DHCP lease anyway.
            Text(profile.displayName, fontSize = 14.sp)
            // Second line: model only, dimmed. Skipped when the
            // displayName already equals the model (to avoid showing
            // "Canon EOS 6D" twice).
            if (profile.displayName != profile.model) {
                Text(
                    profile.model,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Long-press dialog for a saved camera tile. Two stacked sections:
 *
 *  1. **Rename** — text field that edits the user alias. Save commits
 *     a non-blank string; clearing the field commits null which makes
 *     the tile fall back to the auto-resolved displayName cascade
 *     (friendlyName → model+serial → model).
 *
 *  2. **Forget this camera** — destructive action with the same
 *     copy/safety as the original delete dialog, sitting below the
 *     rename section so a user mid-rename doesn't accidentally
 *     trigger it.
 */
@Composable
private fun CameraProfileEditDialog(
    profile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile,
    onDismiss: () -> Unit,
    onRename: (String?) -> Unit,
    onForget: () -> Unit,
) {
    var aliasText by remember(profile.cameraMac) {
        mutableStateOf(profile.alias.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit camera") },
        text = {
            Column {
                Text(
                    "Renames only the label shown on this tile. " +
                        "Doesn't change the camera's body nickname.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = aliasText,
                    onValueChange = { aliasText = it },
                    label = { Text("Alias") },
                    placeholder = { Text(profile.displayName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto label: ${profile.displayName}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Forget this camera",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Removes the saved profile. You'll need to re-pair to reconnect.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onForget,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Empty input → clear alias (null); non-blank → save.
                onRename(aliasText.trim().takeIf { it.isNotBlank() })
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StatusBar(
    phoneState: PhoneWifiState,
    connectionState: ConnectionState,
    lastError: String?,
    hasDetectedCameras: Boolean,
    isScanning: Boolean,
    onSettingsClick: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onScanClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = "Phone",
                    // Green when the phone is routable to the camera — as a
                    // hotspot host, as a Wi-Fi station on a no-internet camera
                    // AP, OR on a regular home Wi-Fi WITH internet (the "both
                    // on home Wi-Fi" topology: a Canon body can join the same
                    // router in EOS Utility mode, so this IS a valid camera
                    // path). Red only when no Wi-Fi is up at all.
                    color = when (phoneState) {
                        PhoneWifiState.HotspotActive,
                        PhoneWifiState.ConnectedNoInternet,
                        PhoneWifiState.ConnectedToInternet -> Color(0xFF2E7D32)
                        PhoneWifiState.Off,
                        PhoneWifiState.Unknown -> Color(0xFFC62828)
                    },
                )
                Spacer(Modifier.width(12.dp))
                StatusPill(
                    label = "Camera",
                    // Green when actually connected; yellow when at
                    // least one Canon-OUI device is visible on the
                    // network (camera is reachable but not paired);
                    // amber while pairing; red otherwise.
                    color = when {
                        connectionState == ConnectionState.Connected -> Color(0xFF2E7D32)
                        connectionState == ConnectionState.Connecting -> Color(0xFFF9A825)
                        hasDetectedCameras -> Color(0xFFF9A825)
                        else -> Color(0xFFC62828)
                    },
                )
                Spacer(Modifier.weight(1f))
                when {
                    connectionState == ConnectionState.Connected -> {
                        TextButton(onClick = onDisconnectClick) { Text("Disconnect") }
                    }
                    connectionState == ConnectionState.Connecting -> {
                        Text("Connecting…", fontSize = 12.sp)
                    }
                    hasDetectedCameras -> {
                        // ARP / SSDP found cameras — pick from the
                        // list below. The button rescans manually.
                        TextButton(onClick = onScanClick) {
                            Text(if (isScanning) "Scanning…" else "Rescan")
                        }
                    }
                    else -> {
                        // No camera detected yet — primary CTA is Scan.
                        Button(onClick = onScanClick, enabled = !isScanning) {
                            Text(if (isScanning) "Scanning…" else "Scan")
                        }
                    }
                }
            }
            // When we're in the Connecting state, surface the user action
            // that legacy EOS bodies (6D, 5D-III, 7D, 60D…) require:
            // pressing SET on the camera body within ~30 seconds to confirm
            // the pairing prompt that appears on the camera's LCD. Without
            // this guidance the user just sees "Connecting…" indefinitely
            // and the camera's internal timer eventually drops the TCP
            // connection with "Software caused connection abort". R-series
            // bodies show no prompt and reply instantly, so the guidance is
            // harmless when not needed.
            if (connectionState == ConnectionState.Connecting) {
                Text(
                    text = "Pairing — check the camera LCD and press SET within ~30 seconds.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (lastError != null) {
                Text(
                    text = lastError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * One card per Canon-OUI device on the local network. Tap connects.
 * The currently-connected camera shows a green tick and is non-tappable.
 */
@Composable
private fun DetectedCamerasList(
    cameras: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera>,
    isConnected: Boolean,
    isConnecting: Boolean,
    onTapCamera: (com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        cameras.forEach { cam ->
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .let { m ->
                        if (isConnected || isConnecting) m
                        else m.clickable { onTapCamera(cam) }
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Status dot — green when this is the active
                    // connection, yellow when reachable but not paired.
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isConnected) Color(0xFF2E7D32)
                                else Color(0xFFF9A825),
                                shape = androidx.compose.foundation.shape.CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cam.friendlyName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        Text(
                            text = "${cam.ip} • ${cam.mac}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isConnected) {
                        Text(
                            text = "✓",
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun DataSyncBudgetBanner(budget: CanonSyncComponent.DataSyncBudget) {
    // Banner only renders when the dataSync FGS budget is non-trivial.
    // Opening this screen counts as foregrounding the app, which refills the
    // system budget — so a user who reaches the screen mid-warning should
    // never linger here. The banner exists for the edge case where they got
    // here from a quick check, and to communicate the "stopped" state if the
    // service had to soft-stop while they were elsewhere in the app.
    when (budget) {
        CanonSyncComponent.DataSyncBudget.Comfortable -> Unit
        is CanonSyncComponent.DataSyncBudget.Warning -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFF9A825),
        ) {
            Text(
                text = "About ${budget.minutesRemaining} minutes left before Android pauses " +
                    "background capture. Keep this screen open to stay running.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = Color.Black,
            )
        }
        CanonSyncComponent.DataSyncBudget.Stopped -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                text = "Background capture paused — Android's 6-hour limit was reached. " +
                    "Tap Connect to start a new session.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Pretty-print a SAF tree URI like
 *   `content://com.android.externalstorage.documents/tree/primary%3APictures%2FRAZStudio`
 * into a human-readable path:
 *   `Pictures/RAZStudio`
 *
 * Decodes the last path segment, drops the leading `primary:` /
 * `home:` / `{volumeId}:` storage prefix, and converts the URI
 * encoding back to slashes. Returns the raw URI string if anything
 * unexpected shows up — the user might be on a non-standard
 * provider where we can't decode safely.
 */
private fun prettySafPath(uri: Uri): String {
    val lastSegment = uri.lastPathSegment ?: return uri.toString()
    val decoded = runCatching {
        java.net.URLDecoder.decode(lastSegment, "UTF-8")
    }.getOrNull() ?: return uri.toString()
    // Strip the volume prefix (`primary:`, `home:`, or `{uuid}:`).
    val colon = decoded.indexOf(':')
    val path = if (colon >= 0) decoded.substring(colon + 1) else decoded
    return path.takeIf { it.isNotBlank() } ?: "(root)"
}

/**
 * Section 1 of the main screen: working directory.
 *
 * Layout, top to bottom:
 *   - "Working directory" section title
 *   - [+ New Folder] button — primary-colour pill, full width
 *   - Current path line — small label + pretty-printed SAF path
 *
 * Both controls live in one Surface so they read as a single
 * "where am I saving?" panel rather than two unrelated rows.
 */
@Composable
private fun WorkingDirSection(
    workingDirUri: Uri?,
    newFolderEnabled: Boolean,
    onNewFolderClick: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Working directory",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = newFolderEnabled, onClick = onNewFolderClick),
                shape = RoundedCornerShape(12.dp),
                color = if (newFolderEnabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (newFolderEnabled) "New Folder"
                        else "Connect a camera to start a capture folder",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = workingDirUri?.let { prettySafPath(it) }
                    ?: "(not set — pick one in app Settings)",
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Section 2 of the main screen: camera actions.
 *
 * Three equally-sized card tiles arranged in a single row:
 *   1. Browse Camera Photos — opens the SD-card browser dialog.
 *   2. Live Remote Shooting — navigates to the live preview screen.
 *   3. Batch Download — pulls every photo from the card and keeps
 *      watching every 10 s for new shots.
 *
 * The whole section is mounted by the caller only when the PTP
 * session is live; on disconnect the parent screen unmounts this
 * and also closes any dialogs / canvases spawned from these
 * actions.
 */
@Composable
private fun CameraActionsSection(
    onBrowsePhotos: () -> Unit,
    onLiveRemoteShoot: () -> Unit,
    onBatchDownload: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Camera",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.FolderOpen,
                    label = "Browse Camera Photos",
                    onClick = onBrowsePhotos,
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CameraAlt,
                    label = "Live Remote Shooting",
                    onClick = onLiveRemoteShoot,
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.DownloadForOffline,
                    label = "Batch Download",
                    onClick = onBatchDownload,
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text = label,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Empty-state replacement for "no working directory yet". Launches the
 * SAF tree picker inline so the user grants Canon Sync access to e.g.
 * `Pictures/RAZStudio` without leaving this screen. The picked tree URI
 * is persisted via [CanonSyncComponent.setWorkingDir] which immediately
 * flips [CanonSyncComponent.workingDirUri] non-null → the FolderGrid
 * replaces this empty state on the next recomposition.
 */
@Composable
private fun EmptyWorkingDirPicker(onPick: (Uri) -> Unit) {
    val picker = rememberFolderPicker(onSuccess = onPick)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp),
            )
            Text(
                text = "Pick the folder where downloaded photos will be saved.\n" +
                    "Tap below to select e.g. Pictures/RAZStudio.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { picker.pickFolder() }) {
                Text("Pick output folder")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderGrid(
    folders: List<CanonSyncComponent.FolderTileState>,
    armedUri: Uri?,
    onTap: (CanonSyncComponent.FolderTileState) -> Unit,
    onLongPress: (CanonSyncComponent.FolderTileState) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it.uri.toString() }) { tile ->
            FolderTile(
                tile = tile,
                isArmed = tile.uri == armedUri,
                onTap = { onTap(tile) },
                onLongPress = { onLongPress(tile) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    tile: CanonSyncComponent.FolderTileState,
    isArmed: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = if (isArmed) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Column {
                Text(
                    text = tile.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${tile.fileCount} files",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isArmed) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Armed",
                    modifier = Modifier.align(Alignment.TopEnd),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ConnectionSettingsDialog(
    formatMode: FormatMode,
    onFormatModeChange: (FormatMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isBatteryExempt = remember(context) { BatteryOptimizationHelper.isExempt(context) }
    val oemGuidance = remember { OemKeepAliveGuide.guidanceFor() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canon Sync Settings") },
        text = {
            Column {
                Text("Download Format", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                FormatRadioRow("RAW only (CR2 / CR3)", formatMode == FormatMode.RAW) {
                    onFormatModeChange(FormatMode.RAW)
                }
                FormatRadioRow("JPEG only", formatMode == FormatMode.JPEG) {
                    onFormatModeChange(FormatMode.JPEG)
                }
                FormatRadioRow("RAW + JPEG", formatMode == FormatMode.RAW_AND_JPEG) {
                    onFormatModeChange(FormatMode.RAW_AND_JPEG)
                }

                Spacer(Modifier.height(16.dp))
                Text("Keep alive", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isBatteryExempt) {
                        "Battery optimization: Exempt"
                    } else {
                        "Android may kill Canon Sync in the background during long shoots."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                BatteryOptimizationHelper.openBatteryOptimizationSettings()
                            )
                        }
                    },
                ) {
                    Text(
                        if (isBatteryExempt) "Manage battery optimization"
                        else "Allow unrestricted battery use",
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${oemGuidance.vendorName}: ${oemGuidance.instructions}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (oemGuidance.intent != null) {
                    TextButton(
                        onClick = {
                            runCatching { context.startActivity(oemGuidance.intent) }
                        },
                    ) {
                        Text("Open ${oemGuidance.vendorName} keep-alive settings")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Wi-Fi topology", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Two supported setups:\n" +
                        "• Phone hotspot — the phone hosts the Wi-Fi; the " +
                        "camera joins it as a client (best for the EOS 6D " +
                        "and older bodies with no infrastructure mode).\n" +
                        "• Home Wi-Fi — phone and camera both join the same " +
                        "router (set the camera to \"Remote control (EOS " +
                        "Utility)\" and pick your home network). No hotspot " +
                        "needed; the phone can keep mobile data on.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Inline filter chip used in the camera-browser dialog. Compact, single-
 * tap, no radio dot — visually consistent with Material 3's FilterChip
 * pattern but light on imports.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BrowserFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun FormatRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("e.g. Wedding-2026-05-29") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Bottom-dialog gallery of every image on the connected camera's SD card.
 * Mirrors Camera Connect's "View images on camera" flow: a flat list (no
 * folder traversal — the standard-PTP enumeration walks every folder
 * inline via parent=0xFFFFFFFF "all subfolders"), each row tappable to
 * trigger an 0x9107 chunked download into the current session folder.
 *
 * Busy state grays the list during enumeration AND during single-file
 * downloads — the command channel mutex serialises both, so concurrent
 * UI taps wouldn't help anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraBrowserDialog(
    photos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
    thumbs: Map<Int, java.io.File>,
    busy: Boolean,
    onRefresh: () -> Unit,
    formatMode: FormatMode,
    onFormatModeChange: (FormatMode) -> Unit,
    onTilePreview: (com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto) -> Unit,
    onVisibleHandlesChanged: (Set<Int>) -> Unit,
    sortMode: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode,
    onSortModeChange: (com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Camera SD card",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Box {
                    val sortMenuOpen = remember { mutableStateOf(false) }
                    TextButton(onClick = { sortMenuOpen.value = true }) {
                        Text("Sort: ${sortMode.label}", maxLines = 1)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = sortMenuOpen.value,
                        onDismissRequest = { sortMenuOpen.value = false },
                    ) {
                        com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode
                            .entries.forEach { mode ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(mode.label + if (mode == sortMode) "  ✓" else "") },
                                    onClick = { onSortModeChange(mode); sortMenuOpen.value = false },
                                )
                            }
                    }
                }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
            }
            // Filter chip row removed per request — the camera browser
            // always lists RAW + JPEG together. The underlying
            // `formatMode` state remains so the download-side
            // "prefer JPEG / RAW" routing still honours the saved
            // preference; only the inline filter UI is gone.
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${photos.size} ${if (photos.size == 1) "photo" else "photos"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (photos.isEmpty() && !busy) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No files yet — tap Refresh to load.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 2-column tile grid. Tile tap fires onTilePreview which
                // streams the full photo into the local cache and opens
                // the preview canvas. Long-press could later route to
                // direct-download; for now keep it tap-to-preview only.
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                // Report which handles are currently visible so the repo can
                // prioritise their thumbnail downloads over off-screen ones.
                androidx.compose.runtime.LaunchedEffect(gridState) {
                    androidx.compose.runtime.snapshotFlow {
                        gridState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                    }.collect { visibleIndices ->
                        val handles = visibleIndices
                            .mapNotNull { idx -> photos.getOrNull(idx)?.previewHandle }
                            .toSet()
                        onVisibleHandlesChanged(handles)
                    }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        photos,
                        // Use editHandle as the stable key — every photo
                        // has at least one of (raw, jpeg), and editHandle
                        // prefers raw when both exist, so the key is
                        // deterministic across recompositions.
                        key = { it.editHandle },
                    ) { photo ->
                        CameraPhotoTile(
                            photo = photo,
                            thumbFile = thumbs[photo.previewHandle],
                            enabled = !busy,
                            onClick = { onTilePreview(photo) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Loading state for the photo preview canvas — full-screen spinner +
 * filename while the camera streams the bytes. Tapping outside or
 * pressing Back dismisses.
 */
@Composable
private fun PhotoPreviewLoading(
    filename: String,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = "Downloading $filename…",
                    color = Color.White,
                )
                Text(
                    text = "First load can take 5–15 s over the camera Wi-Fi.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Photo preview canvas — the popup that opens once a tile's bytes
 * finish streaming. Displays the photo edge-to-edge with two
 * top-right action buttons:
 *  - Edit: routes to RAWEditor's workspace selector with this photo
 *    as the source (TODO wiring).
 *  - Download: copies the cached file into the user's working
 *    directory. If no working dir is set, dismisses the canvas and
 *    surfaces the "+ New Folder" picker (TODO wiring).
 *
 * The rendered bitmap is decoded once from the cached file via
 * [BitmapFactory] with a downsampling factor that keeps it under
 * a few MB in memory — sufficient for a phone display, and avoids
 * OOM on full 25 MP CR2/JPG.
 */
/**
 * In-memory decoded-bitmap LRU capacity for the photo preview canvas.
 * Each entry is a full-res ARGB-8888 bitmap (~35 MB for a 3648×2432
 * 6D JPEG), so we cap at 4 to keep peak heap < 160 MB on heap-limited
 * devices. The repo's file-level LRU is 9, so this is the *inner*
 * window — most-recently-viewed bitmaps stay decoded; further-out
 * pages decode on demand from the still-cached file.
 */
private const val BITMAP_LRU_CAPACITY = 4

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoPreviewCanvas(
    state: com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository.PreviewState.Ready,
    photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto?,
    inProgress: Boolean,
    progressLabel: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    /**
     * Ordered photo list (filtered by formatMode) for the swipe pager.
     * Empty disables swipe.
     */
    orderedPhotos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto> = emptyList(),
    currentIndex: Int = -1,
    /** Fired when the user swipes to a new page. */
    onSwipeToIndex: (Int) -> Unit = {},
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Decoded-bitmap cache keyed by absolute file path.
        //
        // Why we keep this here (canvas-scope) on top of the repo's
        // file LRU: each `BitmapFactory.decodeFile` on a 3-4 MB JPEG
        // takes 50-150 ms AND allocates ~35 MB of ARGB pixels for a
        // 3648×2432 frame. With the user swiping back and forth
        // between 2-3 photos, the same files were being re-decoded
        // every visit — 4 decodes in 4 seconds, observed in adb. A
        // Compose-scope cache keyed by path avoids that without
        // changing semantics: a file's bytes don't change, so the
        // decoded bitmap is also stable.
        //
        // We cap the in-memory bitmap LRU at [BITMAP_LRU_CAPACITY]
        // so phones with limited heap don't OOM during long browse
        // sessions. The dialog disposal drops the map entirely;
        // dropping the canvas also lets Compose GC the bitmaps.
        val bitmapLru = androidx.compose.runtime.remember {
            object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(
                /* initialCapacity = */ BITMAP_LRU_CAPACITY * 2,
                /* loadFactor = */ 0.75f,
                /* accessOrder = */ true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>,
                ): Boolean = size > BITMAP_LRU_CAPACITY
            }
        }
        val cachePath = state.cachedFile.absolutePath
        val cachedBitmap = androidx.compose.runtime.remember(cachePath) { bitmapLru[cachePath] }
        val imageBitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
            initialValue = cachedBitmap,
            key1 = cachePath,
        ) {
            // Already decoded? Skip the IO + JPEG-decode hop.
            if (cachedBitmap != null) {
                value = cachedBitmap
                return@produceState
            }
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bmp = decodePhotoFile(state.cachedFile)
                    android.util.Log.i(
                        "CanonSync",
                        "PhotoPreviewCanvas.decode: file=${state.cachedFile.name} " +
                            "size=${state.cachedFile.length()}B " +
                            "result=${bmp?.let { "${it.width}x${it.height}" } ?: "null"}",
                    )
                    bmp?.asImageBitmap()?.also { bitmapLru[cachePath] = it }
                }.getOrNull()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // Pager body. We page over the photo list so left/right
            // swipes flip to the previous/next photo. The page-to-photo
            // contract: page N renders the photo at orderedPhotos[N].
            // Visually we only paint the CURRENT page (other pages
            // remain placeholders) — the actual download + decode for
            // a neighbour photo happens in the background once
            // `onSwipeToIndex` fires and the repo's LRU prefetch warms it.
            //
            // When orderedPhotos is empty (e.g. list shifted under us
            // mid-session), fall back to the static single-photo render.
            val swipeable = orderedPhotos.isNotEmpty() && currentIndex >= 0
            if (swipeable) {
                val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                    initialPage = currentIndex,
                    pageCount = { orderedPhotos.size },
                )
                // Drive `onSwipeToIndex` whenever the user settles on a
                // new page. We use `settledPage` (not `currentPage`) so
                // we don't kick off downloads while the user is still
                // mid-drag.
                androidx.compose.runtime.LaunchedEffect(pagerState) {
                    androidx.compose.runtime.snapshotFlow { pagerState.settledPage }
                        .collect { settled: Int ->
                            if (settled != currentIndex && settled in orderedPhotos.indices) {
                                onSwipeToIndex(settled)
                            }
                        }
                }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (page == currentIndex) {
                            val bmp = imageBitmap
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp,
                                    contentDescription = state.filename,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                    Text("Decoding…", color = Color.White)
                                }
                            }
                        } else {
                            // Neighbour page placeholder while the user
                            // mid-swipes. The repo's LRU prefetch
                            // populates the cache so the real photo lands
                            // by the time `settledPage` flips.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                val name = orderedPhotos.getOrNull(page)?.displayName ?: ""
                                Text(name, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                // Photo body, centered & letterboxed.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = imageBitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = state.filename,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text("Decoding…", color = Color.White)
                        }
                    }
                }
            }
            // Top bar: filename + close + Edit + Download.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.filename,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Surface what variant Edit / Download will fetch
                    // — RAW preferred when available, else JPG.
                    photo?.let { p ->
                        val variant = when {
                            p.hasRaw && p.hasJpeg -> "RAW+JPG (Edit/Download → CR2)"
                            p.hasRaw -> "RAW only → CR2"
                            else -> "JPG only → JPG"
                        }
                        Text(
                            text = variant,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        // Dual-ISO callout — same filename heuristic the
                        // grid uses, surfaced here so the user knows
                        // they're about to download a file that needs
                        // post-processing before normal demosaic gives
                        // a usable result. The actual cr2hdr-equivalent
                        // bake will be wired into Edit / Download in a
                        // follow-up; this is the visibility-first stage.
                        val isDualIso = p.hasRaw &&
                            com.RAZStudio.StudioRoom.feature.photo_editor.raw
                                .DualIsoDetector.detectFromFilename(
                                    p.displayName + ".CR2",
                                )
                        if (isDualIso) {
                            Text(
                                text = "⚡ Magic Lantern dual-ISO — needs blend pass before editing.",
                                color = Color(0xFFFFC107),
                                fontSize = 11.sp,
                                maxLines = 2,
                            )
                        }
                    }
                }
                TextButton(onClick = onEdit, enabled = !inProgress) {
                    Text("Edit", color = Color.White.copy(alpha = if (inProgress) 0.4f else 1f))
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onDownload, enabled = !inProgress) {
                    Text("Download")
                }
            }
            // Progress overlay — covers the canvas with a dim shade
            // + spinner + label while the chunked transfer runs. The
            // Edit / Download buttons stay visible but disabled so
            // the user can see what's happening without re-tapping.
            if (inProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = progressLabel,
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = "RAW files can take 30–60 s on Wi-Fi.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPhotoTile(
    photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto,
    thumbFile: java.io.File?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Decode the thumbnail bitmap off the main thread. Each tile has
    // its own produceState keyed on the file path, so a thumb arriving
    // mid-scroll only re-runs the decode for that single tile.
    val thumbBitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = thumbFile?.absolutePath,
    ) {
        value = if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    decodePhotoFile(thumbFile)?.asImageBitmap()
                }.getOrNull()
            }
        } else null
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Thumbnail box — shows the JPEG thumb if we've fetched it,
            // otherwise an icon placeholder while the batch background
            // job is still pulling thumbs for this photo.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumbBitmap
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = photo.displayName,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.FolderImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                // Magic Lantern dual-ISO badge — surfaced when the
                // camera saved the shot with the `DUAL` filename
                // prefix. Cheap (filename regex), no per-file I/O.
                // The full bayer-level confirmation only runs at
                // edit-time. CR2/CR3 extensions are inferred from the
                // photo's hasRaw flag — the displayName already
                // carries the IMG/DUAL prefix.
                val isDualIso = photo.hasRaw &&
                    com.RAZStudio.StudioRoom.feature.photo_editor.raw
                        .DualIsoDetector.detectFromFilename(
                            photo.displayName + ".CR2",
                        )
                if (isDualIso) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xCCFFA000),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                    ) {
                        Text(
                            text = "⚡ Dual-ISO",
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                horizontal = 6.dp,
                                vertical = 2.dp,
                            ),
                        )
                    }
                }
                // Format + EXIF overlay (bottom strip). The format token always
                // shows; aperture / ISO / lens fill in once that photo's EXIF is
                // loaded (Stage 2) — same strip, so it stays neat.
                val infoLine = buildList {
                    if (photo.formatLabel.isNotEmpty()) {
                        add(photo.formatLabel + if (photo.hasRaw && photo.hasJpeg) "+J" else "")
                    }
                    photo.apertureF?.let { add("f/" + "%.1f".format(it).trimEnd('0').trimEnd('.')) }
                    photo.isoValue?.let { add("ISO$it") }
                    photo.lensModel?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString("  ·  ")
                if (infoLine.isNotEmpty()) {
                    Surface(
                        color = Color(0x99000000),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            text = infoLine,
                            color = Color.White,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = photo.displayName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Format badge: shows which siblings the camera has
                // for this shot. "RAW+JPG" both sides of the pair,
                // "RAW" or "JPG" on its own when the camera was in
                // single-format mode.
                val badge = when {
                    photo.hasRaw && photo.hasJpeg -> "RAW+JPG"
                    photo.hasRaw -> "RAW"
                    else -> "JPG"
                }
                Text(
                    text = badge,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Decode a camera-side preview file into a [android.graphics.Bitmap].
 *
 * The 6D's `EOS_GetThumbEx (0x910A)` returns whichever embedded
 * preview the camera holds for the queried handle:
 *  - **Plain JPEG bytes** (CR2 returns a 1620×1080 embedded JPEG as
 *    a `JPEGInterchangeFormat` strip)
 *  - **A full CR2 RAW container** (Canon TIFF starting `II*\0CR\2\0`,
 *    with the preview JPEG at the `JPEGInterchangeFormat` TIFF tag
 *    in IFD0)
 *
 * If the bytes already start with the JPEG SOI marker (`0xFF 0xD8`),
 * decode directly. Otherwise treat as TIFF: parse IFD0, find
 * `JPEGInterchangeFormat (0x0201)` + `JPEGInterchangeFormatLength
 * (0x0202)`, read those bytes from the file, and decode.
 */
private fun decodePhotoFile(file: java.io.File): android.graphics.Bitmap? {
    if (!file.exists() || file.length() < 16) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    // Path 1: bytes are a plain JPEG → decode directly.
    val (jpegBytes: ByteArray, jpegOffset: Int, jpegLen: Int) = when {
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
            Triple(bytes, 0, bytes.size)
        else -> {
            // Path 2: TIFF / CR2. The 6D's `EOS_GetThumbEx (0x910A)` on a
            // CR2 handle returns the file's TIFF metadata header followed
            // by the embedded JPEG preview. Scan for the JPEG SOI marker
            // (FF D8 FF) and decode from there to EOF.
            val soi = findSoiMarker(bytes) ?: return null
            Triple(bytes, soi, bytes.size - soi)
        }
    }
    val raw = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, jpegOffset, jpegLen)
        ?: return null
    // EXIF orientation pass. Parses the Orientation tag (0x0112) from
    // the JPEG bytes we just decoded and rotates/flips the bitmap so
    // portraits stand up and landscapes orient correctly without the
    // user tilting the phone. Hand-rolled mini-parser to avoid pulling
    // in the androidx.exifinterface dep just for one u16 read.
    val orient = parseJpegOrientation(jpegBytes, jpegOffset, jpegLen) ?: 1
    android.util.Log.i(
        "CanonSync.Thumb",
        "decodePhotoFile: ${file.name} jpegLen=$jpegLen orient=$orient raw=${raw.width}x${raw.height}",
    )
    return applyExifOrientation(raw, orient)
}

/**
 * Apply the EXIF Orientation tag (1..8) to a decoded bitmap. Returns
 * the input bitmap untouched when no transform is needed. Recycles
 * the input only when a new bitmap is allocated.
 *
 *  1=Normal, 2=FlipH, 3=Rot180, 4=FlipV,
 *  5=Transpose, 6=Rot90, 7=Transverse, 8=Rot270
 */
private fun applyExifOrientation(
    src: android.graphics.Bitmap,
    orientation: Int,
): android.graphics.Bitmap {
    if (orientation <= 1 || orientation > 8) return src
    val m = android.graphics.Matrix()
    when (orientation) {
        2 -> m.preScale(-1f, 1f)
        3 -> m.postRotate(180f)
        4 -> { m.postRotate(180f); m.preScale(-1f, 1f) }
        5 -> { m.postRotate(90f);  m.preScale(-1f, 1f) }
        6 -> m.postRotate(90f)
        7 -> { m.postRotate(-90f); m.preScale(-1f, 1f) }
        8 -> m.postRotate(-90f)
        else -> return src
    }
    val out = runCatching {
        android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrNull() ?: return src
    if (out !== src) src.recycle()
    return out
}

/**
 * Read the EXIF Orientation tag (0x0112, type SHORT) from a JPEG byte
 * range. Returns 1..8 if the tag is present and valid, null otherwise
 * (treat as "normal"). Walks the standard JPEG marker chain looking
 * for the APP1 EXIF segment, then parses TIFF IFD0 for the
 * Orientation tag. Endianness-aware. ~80 lines but no new dep.
 */
private fun parseJpegOrientation(buf: ByteArray, offset: Int, length: Int): Int? {
    // JPEG SOI = FF D8. Markers afterwards are FF Mn (Mn != 00, FF).
    var p = offset
    val end = offset + length
    if (p + 4 > end || buf[p] != 0xFF.toByte() || buf[p + 1] != 0xD8.toByte()) return null
    p += 2
    while (p + 4 <= end) {
        if (buf[p] != 0xFF.toByte()) return null
        // Skip fill bytes (FF FF ...).
        while (p + 1 < end && buf[p + 1] == 0xFF.toByte()) p++
        if (p + 1 >= end) return null
        val marker = buf[p + 1].toInt() and 0xFF
        // Standalone markers (no length field): SOI (D8), EOI (D9),
        // RSTn (D0..D7), TEM (01). We've already passed SOI.
        if (marker == 0xD9 || (marker in 0xD0..0xD7) || marker == 0x01) {
            p += 2; continue
        }
        if (p + 4 > end) return null
        // Length is big-endian, includes the 2 length bytes themselves.
        val segLen = ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
        if (segLen < 2 || p + 2 + segLen > end) return null
        if (marker == 0xE1 && segLen > 8) {
            // APP1 — check for "Exif\0\0" header at p+4.
            val hdr = p + 4
            if (buf[hdr] == 0x45.toByte() && buf[hdr + 1] == 0x78.toByte() &&
                buf[hdr + 2] == 0x69.toByte() && buf[hdr + 3] == 0x66.toByte() &&
                buf[hdr + 4] == 0x00.toByte() && buf[hdr + 5] == 0x00.toByte()
            ) {
                // TIFF header starts at hdr+6.
                val tiff = hdr + 6
                if (tiff + 8 > end) return null
                val little = buf[tiff] == 0x49.toByte() && buf[tiff + 1] == 0x49.toByte()
                val big = buf[tiff] == 0x4D.toByte() && buf[tiff + 1] == 0x4D.toByte()
                if (!little && !big) return null
                fun u16(off: Int): Int =
                    if (little) (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
                    else ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
                fun u32(off: Int): Int =
                    if (little) (buf[off].toInt() and 0xFF) or
                        ((buf[off + 1].toInt() and 0xFF) shl 8) or
                        ((buf[off + 2].toInt() and 0xFF) shl 16) or
                        ((buf[off + 3].toInt() and 0xFF) shl 24)
                    else ((buf[off].toInt() and 0xFF) shl 24) or
                        ((buf[off + 1].toInt() and 0xFF) shl 16) or
                        ((buf[off + 2].toInt() and 0xFF) shl 8) or
                        (buf[off + 3].toInt() and 0xFF)
                if (u16(tiff + 2) != 0x002A) return null
                val ifd0 = tiff + u32(tiff + 4)
                if (ifd0 + 2 > end) return null
                val nEntries = u16(ifd0)
                var ent = ifd0 + 2
                repeat(nEntries) {
                    if (ent + 12 > end) return@repeat
                    val tag = u16(ent)
                    if (tag == 0x0112) {
                        // Orientation. Value is a SHORT (2 bytes) in the
                        // first 2 bytes of the value-or-offset field at
                        // ent+8. (For SHORT count=1, value is inlined.)
                        return u16(ent + 8)
                    }
                    ent += 12
                }
                return null
            }
        }
        p += 2 + segLen
    }
    return null
}

/**
 * Find the first `FF D8 FF` (JPEG SOI + first marker byte) in [bytes].
 * Returns the byte offset or null if not found. We require three
 * bytes (SOI + marker prefix) to avoid false positives from raw
 * sensor data that happens to contain `FF D8`.
 */
private fun findSoiMarker(bytes: ByteArray): Int? {
    for (i in 0 until bytes.size - 2) {
        if (bytes[i] == 0xFF.toByte() &&
            bytes[i + 1] == 0xD8.toByte() &&
            bytes[i + 2] == 0xFF.toByte()
        ) {
            return i
        }
    }
    return null
}

private fun ByteArray.toU16(littleEndian: Boolean): Int =
    if (littleEndian) (this[0].toInt() and 0xFF) or ((this[1].toInt() and 0xFF) shl 8)
    else ((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)

private fun ByteArray.toU32(littleEndian: Boolean): Long =
    if (littleEndian) {
        (this[0].toLong() and 0xFF) or
            ((this[1].toLong() and 0xFF) shl 8) or
            ((this[2].toLong() and 0xFF) shl 16) or
            ((this[3].toLong() and 0xFF) shl 24)
    } else {
        ((this[0].toLong() and 0xFF) shl 24) or
            ((this[1].toLong() and 0xFF) shl 16) or
            ((this[2].toLong() and 0xFF) shl 8) or
            (this[3].toLong() and 0xFF)
    }

private fun humanReadableBytes(bytes: Long): String = when {
    bytes <= 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}

/**
 * Full runtime-permission set for a robust Canon discovery pass.
 *
 * The matrix:
 *   • API ≤ 30  → FINE_LOCATION is the gatekeeper for Wi-Fi scan results
 *                 and for reading the connected SSID/BSSID.
 *   • API 31–32 → adds BLUETOOTH_SCAN / BLUETOOTH_CONNECT (BT discovery
 *                 path for R-series bodies that advertise via BLE).
 *   • API ≥ 33  → NEARBY_WIFI_DEVICES replaces the location requirement
 *                 for Wi-Fi scanning, POST_NOTIFICATIONS becomes runtime.
 *                 We STILL request FINE_LOCATION because many OEM forks
 *                 (Samsung, Xiaomi, Transsion) silently keep the legacy
 *                 location gate on `getConnectionInfo()` results — this
 *                 lets us read the connected camera AP's BSSID/SSID for
 *                 subnet-aware sweeps. Camera permission is requested
 *                 only when the QR-pair flow is invoked (not at scan).
 *
 * Per the Canon Camera Connect (jp.co.canon.ic.cameraconnect) manifest
 * inspection, this matches their declared runtime-prompted set.
 */
private fun requiredPermissions(): Array<String> = buildList {
    // Wi-Fi scan + connected-AP introspection.
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    // T+ — Wi-Fi nearby devices without forcing the location gate.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    // S+ — Bluetooth runtime split. Some Canon bodies use BLE for the
    // hand-shake before handing off to Wi-Fi (R5/R6/etc.). Asking up
    // front means we don't lose that path on a later code-path switch.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()
