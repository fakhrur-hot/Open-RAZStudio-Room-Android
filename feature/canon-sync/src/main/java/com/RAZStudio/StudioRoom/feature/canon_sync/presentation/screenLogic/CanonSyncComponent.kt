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

package com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.PhoneWifiObserver
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.PhoneWifiState
import com.RAZStudio.StudioRoom.feature.canon_sync.service.SessionDurationTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CanonSyncComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @ApplicationContext private val context: Context,
    private val repository: CanonSyncRepository,
    private val settingsManager: SettingsManager,
    private val phoneWifiObserver: PhoneWifiObserver,
    private val sessionDurationTracker: SessionDurationTracker,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    /**
     * Live read of the Default Output folder (the same `saveFolderUri` the
     * rest of the app uses). If the user changes it from app Settings, the
     * working-directory header reflects the new path on next emission.
     *
     * Filters to only SAF tree URIs (`content://…/tree/…`). The rest of the
     * app sometimes stores legacy file:// paths or empty strings in
     * `saveFolderUri`, but Canon Sync only knows how to operate on a tree
     * URI — `DocumentFile.fromTreeUri` throws `IllegalArgumentException` on
     * anything else. Surface null for non-tree storage so the UI shows the
     * "set a Default Output folder" prompt.
     */
    val workingDirUri: StateFlow<Uri?> = settingsManager.settingsState
        .map { state ->
            state.saveFolderUri
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme == "content" }
        }
        .stateIn(componentScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), null)

    private val foldersInvalidationCount = MutableStateFlow(0)

    /**
     * Sub-folders directly under [workingDirUri]. Re-queried whenever the
     * working directory changes or [refreshFolders] is called. SAF
     * `DocumentFile.listFiles` is the slow part of the API surface so we only
     * invoke it on coarse triggers, not per UI tick.
     */
    val folders: StateFlow<List<FolderTileState>> = combine(
        workingDirUri,
        foldersInvalidationCount,
    ) { uri, _ -> uri }
        .map(::listFoldersUnder)
        // SAF DocumentFile.listFiles() is a content-provider RPC; on a
        // working folder full of session subfolders + photos it can take
        // multiple seconds. Without flowOn(IO) it runs on the upstream
        // dispatcher (componentScope = Main) and ANRs the screen.
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(componentScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), emptyList())

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val availableCameras = repository.availableCameras
    val isScanning = repository.isScanning

fun startCameraScanLoop() = repository.startCameraScanLoop()
    fun stopCameraScanLoop() = repository.stopCameraScanLoop()
    // performOneScan() does a 1.5 s blocking SSDP listen + /proc/net/arp
    // read. Both must NOT run on Decompose's Main-dispatcher
    // componentScope, or the screen freezes for ~1.5 s per call (the
    // Choreographer "Skipped 934 frames" symptom). Push to IO.
    fun scanForCanonNow() = componentScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        repository.scanForCanonNow()
    }
    val armedFolderUri: StateFlow<Uri?> = repository.armedFolderUri
    val formatMode: StateFlow<FormatMode> = repository.formatMode
    val sortMode: StateFlow<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode> =
        repository.sortMode
    val lastError: StateFlow<String?> = repository.lastError
    val phoneWifiState: StateFlow<PhoneWifiState> = phoneWifiObserver.state

    init {
        // Start hotspot-status polling so the Phone pip can flip green
        // when the user enables Android's Wi-Fi hotspot. The network
        // callbacks don't fire for AP changes, so we poll WifiManager
        // every 2 s. Bound to componentScope — automatically cancelled
        // when the screen is left.
        phoneWifiObserver.startHotspotPolling(componentScope)
    }

    /**
     * dataSync FGS budget summary for the UI banner. The Android 15+ system
     * limit is 6 h cumulative `dataSync` runtime, refilled only when the user
     * foregrounds the app. The component opens the screen → we already
     * foregrounded the app → [SessionDurationTracker] resets, so a user who
     * opens Canon Sync mid-warning sees [DataSyncBudget.Comfortable]. The
     * banner appears only when the user is on some *other* foreground screen
     * (e.g. the photo editor) while the capture service runs.
     */
    val dataSyncBudget: StateFlow<DataSyncBudget> = sessionDurationTracker.state
        .map { tracked ->
            val total = tracked.totalMillisAt(android.os.SystemClock.elapsedRealtime())
            when {
                total >= SessionDurationTracker.SOFT_STOP_THRESHOLD_MILLIS -> DataSyncBudget.Stopped
                total >= SessionDurationTracker.WARN_THRESHOLD_MILLIS -> DataSyncBudget.Warning(
                    minutesRemaining = ((SessionDurationTracker.BUDGET_MILLIS - total) /
                        ONE_MINUTE_MILLIS).toInt().coerceAtLeast(0),
                )
                else -> DataSyncBudget.Comfortable
            }
        }
        .stateIn(componentScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), DataSyncBudget.Comfortable)

    sealed interface DataSyncBudget {
        data object Comfortable : DataSyncBudget
        data class Warning(val minutesRemaining: Int) : DataSyncBudget
        data object Stopped : DataSyncBudget
    }

    fun refreshFolders() {
        foldersInvalidationCount.value++
    }

    /**
     * Persist a freshly-picked SAF tree URI as Canon Sync's output root.
     * Called from the inline "Pick folder" button on the empty-state UI —
     * lets the user grant access to `Pictures/RAZStudio` (or wherever)
     * without leaving Canon Sync. Once set, [workingDirUri] flips
     * non-null and the FolderGrid renders the subfolders.
     */
    fun setWorkingDir(uri: Uri) {
        componentScope.launch {
            settingsManager.setSaveFolderUri(uri.toString())
        }
    }

    fun createSubfolder(name: String) {
        val parentUri = workingDirUri.value ?: return
        componentScope.launch {
            val sanitized = name.trim().takeIf { it.isNotEmpty() } ?: return@launch
            val parent = runCatching { DocumentFile.fromTreeUri(context, parentUri) }
                .getOrNull() ?: return@launch
            if (!parent.isDirectory || !parent.canWrite()) return@launch
            val existing = parent.findFile(sanitized)
            val created = existing ?: parent.createDirectory(sanitized)
            created?.uri?.let { uri ->
                refreshFolders()
                repository.rearm(uri)
            }
        }
    }

    fun armFolder(uri: Uri) = repository.rearm(uri)
    fun disarmFolder() = repository.disarm()
    fun setFormatMode(mode: FormatMode) = repository.setFormatMode(mode)
    fun setSortMode(mode: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode) =
        repository.setSortMode(mode)

    // ── Camera SD-card browser ─────────────────────────────────────────────
    /**
     * Paired list of camera-side photos, filtered by the user's
     * [FormatMode] setting. One entry per logical shot (RAW + JPG
     * paired into a single [CameraPhoto] when both exist). Empty
     * until [refreshCameraObjects].
     */
    val cameraPhotos = repository.cameraPhotos
    /** True while enumerating or downloading. */
    val cameraBrowserBusy = repository.cameraBrowserBusy

    /** Re-enumerate the camera SD card; idempotent. */
    fun refreshCameraObjects() = repository.refreshCameraObjects()

    /** Download a single camera-side image into the session output folder. */
    fun downloadCameraObject(
        obj: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo,
    ) = repository.downloadCameraObject(obj)

    /**
     * Download the user-chosen variant of a paired photo (RAW or JPG).
     * Returns the resulting SAF Uri via [onComplete] for downstream
     * navigation (e.g. opening in RAWEditor).
     */
    fun downloadCameraPhoto(
        photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto,
        handle: Int,
        filenameExt: String,
        /**
         * Optional override for the destination folder. Useful when
         * the caller just got a folder Uri back from the SAF picker
         * and the [workingDirUri] StateFlow hasn't propagated the
         * persisted value yet.
         */
        explicitFolder: android.net.Uri? = null,
        onComplete: ((android.net.Uri?) -> Unit)? = null,
    ) = repository.downloadCameraPhoto(
        photo = photo,
        handle = handle,
        filenameExt = filenameExt,
        destinationFolder = explicitFolder ?: workingDirUri.value,
        onComplete = onComplete,
    )

    /**
     * Download the photo (RAW-preferred) into the working directory,
     * then jump straight into RAWEditor with the resulting SAF Uri so
     * the user can start editing. Invokes [onComplete] with the saved
     * Uri on success (null on failure) so the canvas can dismiss its
     * progress overlay and surface a Toast.
     */
    fun editCameraPhoto(
        photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto,
        handle: Int,
        filenameExt: String,
        explicitFolder: android.net.Uri? = null,
        onComplete: ((android.net.Uri?) -> Unit)? = null,
    ) = repository.downloadCameraPhoto(
        photo = photo,
        handle = handle,
        filenameExt = filenameExt,
        destinationFolder = explicitFolder ?: workingDirUri.value,
        onComplete = { uri ->
            // The repo invokes this from its IO coroutine. The caller
            // (UI) expects Main-thread state changes, and Decompose's
            // root navigation must NOT run from a background thread —
            // hop to Main before propagating.
            componentScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                onComplete?.invoke(uri)
                if (uri != null) onNavigate(Screen.RawEditor(uri = uri))
            }
        },
    )

    /**
     * Resolve the [CameraPhoto] that contains [previewHandle] (which
     * came from [CanonSyncRepository.PreviewState.Ready]). The canvas
     * uses this to know which siblings exist when the user taps
     * Edit / Download.
     */
    fun findPhotoByHandle(handle: Int): com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto? {
        return cameraPhotos.value.firstOrNull {
            it.rawHandle == handle || it.jpegHandle == handle
        }
    }

    /** Active preview-canvas state (Idle / Loading / Ready w/ cached file). */
    internal val previewState: kotlinx.coroutines.flow.StateFlow<CanonSyncRepository.PreviewState> =
        repository.previewState

    /** Map of handle → small-thumb file for the grid tiles. */
    val gridThumbs: kotlinx.coroutines.flow.StateFlow<Map<Int, java.io.File>> =
        repository.gridThumbs

    /** Start the background batch that fetches a thumb for every photo. */
    fun startGridThumbsFetch(
        photos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
    ) = repository.startGridThumbsFetch(photos)

    /**
     * Called by the grid UI on every scroll frame to update which handles
     * are currently visible. If a fetch is in progress, it restarts so the
     * visible tiles jump to the front of the download queue.
     */
    fun updateVisibleGridHandles(
        handles: Set<Int>,
        photos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
    ) = repository.updateVisibleGridHandles(handles, photos)

    /** Cancel the grid-thumb batch (when user closes browser). */
    fun cancelGridThumbsJob() = repository.cancelGridThumbsJob()

    /** Stream a camera photo into the local preview cache and show the canvas. */
    fun previewCameraObject(
        photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto,
    ) = repository.previewCameraObject(photo)

    /**
     * Big-thumbnail swipe support: prefetch ±2 neighbors of [currentIndex]
     * in [ordered] so left/right swipes are instant. Anything beyond ±3
     * gets LRU-evicted automatically.
     */
    fun prefetchPreviewNeighbors(
        ordered: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
        currentIndex: Int,
    ) = repository.prefetchPreviewNeighbors(ordered, currentIndex)

    /** Close the preview canvas (back press / X tap). */
    fun dismissPreview() = repository.dismissPreview()

    /**
     * In-flight guard for the Connect button. Without this, a user mashing
     * Connect when Wi-Fi is genuinely off produces a storm: each tap fires
     * `permissionLauncher.launch`, the already-granted callback hits
     * `component.connect`, and the repository churns through state flips
     * (Connecting → Failed → Connecting…) at ~150ms cadence. We saw 50+
     * `WrongNetwork` failures in 8 seconds in the 00:58:58 logcat window.
     *
     * This flag flips true the moment we accept a connect intent and only
     * flips back when [repository.connect] returns. The Connect button
     * caller also gates on [connectionState] != Connecting, so the
     * permissionLauncher path and any future direct callers are both
     * covered.
     */
    private val connectInFlight = MutableStateFlow(false)

    // Tracks the launched connect coroutine so the user can Stop a
    // long-running attempt mid-flight (5 s × 3 attempts × backoff =
    // ~21 s window where a frozen camera otherwise locks the UI).
    private var connectJob: kotlinx.coroutines.Job? = null

    fun connect(
        cameraIp: String = DEFAULT_CAMERA_IP,
        hostName: String = DEFAULT_HOST_NAME,
        preferredProfile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile? = null,
    ) {
        if (!connectInFlight.compareAndSet(expect = false, update = true)) {
            android.util.Log.d("CanonSync", "component.connect: in-flight, ignoring tap")
            return
        }
        connectJob = componentScope.launch {
            try {
                repository.connect(cameraIp, hostName, preferredProfile)
            } finally {
                connectInFlight.value = false
                connectJob = null
            }
        }
    }

    /**
     * User-initiated Stop on a connect attempt. Cancels the launched
     * coroutine so the 3-retry timeout loop aborts immediately, then
     * runs the standard disconnect teardown to clear reconnect-args
     * and per-session caches. Safe to call when nothing is in flight.
     */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        repository.disconnect()
    }

    fun disconnect() = repository.disconnect()

    /** Cold flow of saved camera profiles for the Quick-Connect tile list. */
    val cameraProfiles: kotlinx.coroutines.flow.StateFlow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile>> =
        repository.cameraProfiles.stateIn(
            scope = componentScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    /** Tap on a Quick-Connect tile — connect to that specific camera. */
    fun connectToProfile(
        profile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile,
    ) = connect(preferredProfile = profile)

    /** Confirmed delete from the Quick-Connect tile long-press flow. */
    fun forgetProfile(cameraMac: String) {
        componentScope.launch { repository.forgetCameraProfile(cameraMac) }
    }

    /** Save (or clear, with null) a user-typed alias for a saved camera. */
    fun renameProfile(cameraMac: String, alias: String?) {
        componentScope.launch { repository.renameCameraProfile(cameraMac, alias) }
    }

    /**
     * Tap-to-open: enumerate the folder's contents (excluding `.part`
     * staging files) and navigate to the in-app gallery viewer
     * ([Screen.ImagePreview]).
     */
    fun openFolderInGallery(folderUri: Uri) {
        componentScope.launch {
            val folder = runCatching { DocumentFile.fromTreeUri(context, folderUri) }
                .getOrNull() ?: return@launch
            if (!folder.isDirectory) return@launch
            val uris = folder.listFiles()
                .asSequence()
                .filter {
                    it.isFile &&
                        it.name?.endsWith(STAGING_SUFFIX) != true &&
                        it.name?.startsWith("0x") != true
                }
                .map { it.uri }
                .toList()
            onNavigate(Screen.ImagePreview(uris = uris))
        }
    }

    private fun listFoldersUnder(uri: Uri?): List<FolderTileState> {
        if (uri == null) return emptyList()
        // DocumentFile.fromTreeUri throws IllegalArgumentException on a
        // non-tree URI (e.g. an empty string or legacy file:// path that
        // slipped past the upstream filter). Defensive runCatching so the
        // entire `folders` Flow doesn't enter an error state and tear down
        // the component's combine chain.
        val parent = runCatching { DocumentFile.fromTreeUri(context, uri) }
            .getOrNull() ?: return emptyList()
        if (!parent.isDirectory) return emptyList()
        return parent.listFiles()
            .asSequence()
            .filter { it.isDirectory }
            .map { dir ->
                FolderTileState(
                    uri = dir.uri,
                    name = dir.name ?: "(unnamed)",
                    fileCount = dir.listFiles().count { f ->
                        f.isFile &&
                            f.name?.endsWith(STAGING_SUFFIX) != true &&
                            // Legacy 0xHANDLE-named blobs from the
                            // pre-fix 0x1007 fallback path: hide them
                            // from the folder count so the UI stays
                            // clean. The files themselves remain on
                            // disk so the user can delete them
                            // manually if they want.
                            f.name?.startsWith("0x") != true
                    },
                )
            }
            .sortedByDescending { it.uri.toString() }
            .toList()
    }

    data class FolderTileState(
        val uri: Uri,
        val name: String,
        val fileCount: Int,
    )

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): CanonSyncComponent
    }

    companion object {
        private const val STATE_TIMEOUT_MS = 5_000L
        private const val STAGING_SUFFIX = ".part"
        // The Canon EOS 6D in AP-host mode assigns the phone .1 and grabs
        // .2 for itself (atypical — most newer R-series bodies are the AP at
        // .1). For v1 this is a hard-coded default that matches the dev
        // device. The "ARP discovery" follow-up slice will sniff the first
        // non-self IP on /proc/net/arp so any Canon body works regardless of
        // which .x it ends up on.
        private const val DEFAULT_CAMERA_IP = "192.168.1.2"
        private const val DEFAULT_HOST_NAME = "RAZStudio"
        private const val ONE_MINUTE_MILLIS = 60_000L
    }
}
