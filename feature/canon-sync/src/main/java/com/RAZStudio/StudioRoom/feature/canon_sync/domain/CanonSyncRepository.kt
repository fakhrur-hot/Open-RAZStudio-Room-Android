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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.feature.canon_sync.data.CanonSyncPreferences
import com.RAZStudio.StudioRoom.feature.canon_sync.data.SafCaptureTarget
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectResult
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.NetworkBindResult
import com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonWifiClient
import com.RAZStudio.StudioRoom.feature.canon_sync.net.HeartbeatLoop
import com.RAZStudio.StudioRoom.feature.canon_sync.net.NetworkBinder
import com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants
import com.RAZStudio.StudioRoom.feature.canon_sync.net.WftUpnpDiscoverer
import kotlinx.coroutines.flow.first
import com.RAZStudio.StudioRoom.feature.canon_sync.service.CanonSyncCaptureService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.buffer
import okio.sink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the Canon Sync session lifecycle. Composes the wire-layer
 * primitives ([NetworkBinder] → [CanonWifiClient] → [CaptureCoordinator]) so
 * the ViewModel doesn't have to.
 *
 * Singleton-scoped because a Canon session is process-wide — only one camera
 * can be connected at a time, and pulling the screen away mid-shoot must not
 * destroy the active capture (the foreground service slice will rely on this).
 */
@Singleton
internal class CanonSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: CanonSyncPreferences,
    private val settingsManager: com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager,
    /**
     * Hilt-singleton from feature/photo-editor. Used by the Download &
     * Process flow. Eagerly held rather than lazily-allocated so the
     * processor's internal `v3Coordinator` cache persists across the
     * session's repeated start/stop cycles.
     */
    private val downloadAndProcessCoordinator: DownloadAndProcessCoordinator,
) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _availableCameras =
        MutableStateFlow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera>>(
            emptyList(),
        )
    val availableCameras: StateFlow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera>> =
        _availableCameras.asStateFlow()

    /**
     * True while the background scanner is actively probing. Used by
     * the UI to render the "scanning…" affordance distinct from "idle
     * with stale list". Flips false after each [SCAN_LOOP_INTERVAL_MS]
     * tick completes and again on cancel.
     */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanLoopJob: kotlinx.coroutines.Job? = null

    private val _armedFolderUri = MutableStateFlow<Uri?>(null)
    val armedFolderUri: StateFlow<Uri?> = _armedFolderUri.asStateFlow()

    private val _formatMode = MutableStateFlow(FormatMode.RAW)
    val formatMode: StateFlow<FormatMode> = _formatMode.asStateFlow()

    /** SD-browser grid sort order. Default = newest captured first. */
    private val _sortMode = MutableStateFlow(
        com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode.DATE_NEWEST,
    )
    val sortMode: StateFlow<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode> =
        _sortMode.asStateFlow()
    fun setSortMode(mode: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.BrowserSortMode) {
        _sortMode.value = mode
    }

    private val _cameraObjects =
        MutableStateFlow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo>>(
            emptyList()
        )

    /**
     * Manifest of every image on the connected camera's SD card. Empty
     * until [refreshCameraObjects] is called (the operation costs one
     * round-trip per file, so we don't auto-refresh on every state
     * change — the UI lazily triggers it when the browser opens).
     */
    val cameraObjects: StateFlow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo>> =
        _cameraObjects.asStateFlow()

    // ───────────────── Live Remote Shooting state ─────────────────────
    /**
     * Sealed state for the Live Remote Shooting screen. The Idle state
     * is what every other screen sees (no LV stream running). Streaming
     * carries the latest decoded JPEG frame as raw bytes so the UI
     * layer can pass it straight to BitmapFactory.
     */
    sealed interface LiveViewState {
        data object Idle : LiveViewState
        data object Starting : LiveViewState
        /** Active stream — [latestFrame] is the most recent JPEG. */
        data class Streaming(val latestFrame: ByteArray?) : LiveViewState
        data class Error(val message: String) : LiveViewState
    }

    private val _liveViewState = MutableStateFlow<LiveViewState>(LiveViewState.Idle)
    val liveViewState: StateFlow<LiveViewState> = _liveViewState.asStateFlow()

    /**
     * True while a capture sequence (half-press → full-press → release)
     * is in flight. The UI uses this to disable the shutter button
     * during the dead time and surface a progress indicator. Cleared
     * by the end of [triggerShutter] regardless of outcome.
     */
    private val _captureInProgress = MutableStateFlow(false)
    val captureInProgress: StateFlow<Boolean> = _captureInProgress.asStateFlow()

    // ───────────────── Batch download (whole-card pull) ──────────────
    /**
     * State of the "download every photo on the card + keep watching"
     * pipeline driven by [Screen.CanonBatchDownload].
     */
    sealed interface CanonBatchPhase {
        data object Idle : CanonBatchPhase
        /** Enumerating the card via `0x9109 EOS_GetObjectInfoEx`. */
        data object Scanning : CanonBatchPhase
        /** Active download of [currentFilename]; [doneCount] of [totalCount]. */
        data class Running(
            val currentFilename: String,
            val currentHandle: Int,
            val currentBytes: Long,
            val currentBytesTotal: Long,
            val doneCount: Int,
            val skippedCount: Int,
            val totalCount: Int,
        ) : CanonBatchPhase
        /** Initial sweep complete; idle-polling every 10 s for new shots. */
        data class Watching(
            val doneCount: Int,
            val skippedCount: Int,
        ) : CanonBatchPhase
        data class Error(val message: String) : CanonBatchPhase
    }

    /**
     * Format filter for the batch downloader. Independent of the main
     * `FormatMode` chip so the user can pull JPEGs in bulk without
     * disturbing their Browse SD Card filter preference.
     */
    enum class BatchFormat { RAW, JPEG, BOTH }

    private val _batchState = MutableStateFlow<CanonBatchPhase>(CanonBatchPhase.Idle)
    val batchState: StateFlow<CanonBatchPhase> = _batchState.asStateFlow()

    private var batchJob: kotlinx.coroutines.Job? = null

    private var liveViewJob: kotlinx.coroutines.Job? = null

    private val _cameraBrowserBusy = MutableStateFlow(false)
    /** True while the manifest enumeration or a single download is in flight. */
    val cameraBrowserBusy: StateFlow<Boolean> = _cameraBrowserBusy.asStateFlow()

    private var client: CanonWifiClient? = null
    private var coordinatorJob: Job? = null
    private var coordinator: CaptureCoordinator? = null
    /**
     * Per-session live property cache. Constructed in [connect] once the
     * client is alive; nulled in [tearDownSession]. UI layer reaches in
     * via [propertyController] for chip data sources.
     */
    private var _propertyController: EosPropertyController? = null
    private var propertyControllerJob: Job? = null
    /** Public accessor — null when no PTP session. */
    val propertyController: EosPropertyController? get() = _propertyController

    /**
     * Internal helper: launch `op` on the session scope IF a PTP
     * session is live, wrapping it in [runCatching] so a transport
     * exception doesn't crash the supervisor. Returns silently when
     * `client` is null — matches the "fire and forget if connected"
     * semantics of every public action method (doAf, setCaptureDest,
     * driveLens, applyArmedCaptureDestination, etc.).
     *
     * Folds the recurring pattern:
     * ```
     * fun X() {
     *     val activeClient = client ?: return
     *     sessionScope.launch { runCatching { activeClient.op() } }
     * }
     * ```
     * into a one-liner: `fun X() = launchOnClient { it.op() }`.
     */
    private inline fun launchOnClient(
        crossinline op: suspend (CanonWifiClient) -> Unit,
    ) {
        val c = client ?: return
        sessionScope.launch { runCatching { op(c) } }
    }

    /**
     * Read-through view of the active PTP/IP client. Null when no
     * session is open. Used by the LV screen to subscribe to
     * camera-pushed event flows (OLCInfo, property changes) that the
     * coordinator doesn't surface.
     */
    internal val activeClient: CanonWifiClient? get() = client
    private var networkRelease: (() -> Unit)? = null
    /**
     * Connected camera's model/friendly name (from InitCommandAck). Used to
     * name the per-session output subfolder (e.g. "RAZ6D-20260529/").
     * Cleared on disconnect.
     */
    private var currentCameraModel: String? = null

    /**
     * Date stamp captured at session start (first time we needed a
     * subfolder name after connect). Stays stable across midnight so
     * a long shoot stays in one folder. Cleared on disconnect.
     */
    private var sessionStartIsoDate: String? = null

    /**
     * Compute the session-folder name `{CameraName}-{YYYYMMDD}`,
     * sanitised for filesystem use. Caches [sessionStartIsoDate] on
     * first call so subsequent calls return the same folder name even
     * after the wall clock crosses midnight.
     */
    private fun sessionSubfolderName(): String {
        val date = sessionStartIsoDate ?: run {
            val today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            sessionStartIsoDate = today
            today
        }
        val modelSafe = (currentCameraModel ?: "Camera")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "Camera" }
        return "$modelSafe-$date"
    }

    /**
     * Resolve / create the session subfolder under [rootUri]. Returns
     * the subfolder's URI on success, null on failure (read-only root,
     * SAF rejection, etc.). Single source of truth for the per-session
     * directory used by Browse Edit/Download, Batch Download, and
     * Live Remote Shooting.
     */
    private fun ensureSessionSubfolder(rootUri: Uri): Uri? {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
            ?: return null
        if (!root.isDirectory || !root.canWrite()) return null
        val name = sessionSubfolderName()
        val sub = root.findFile(name)?.takeIf { it.isDirectory }
            ?: root.createDirectory(name)
            ?: run {
                Log.w(TAG, "ensureSessionSubfolder: createDirectory('$name') failed")
                return null
            }
        return sub.uri
    }
    /**
     * Release closure for the SSID-pinning NetworkCallback registered after
     * the PTP session goes live. Forces Android to keep the camera AP
     * connected even though it has no validated internet. Null when no
     * pin is active.
     */
    private var ssidPinRelease: (() -> Unit)? = null

    /**
     * Periodic PCHDDCapacity (0x911A) heartbeat that defeats the 6D's
     * Wi-Fi NIC sleep timer. See [HeartbeatLoop] for rationale. Null
     * when no session is active.
     */
    private var heartbeatJob: Job? = null

    /**
     * Last-used connect parameters, captured so the auto-reconnect loop
     * can re-issue them when the 6D drops its Wi-Fi mid-session. Cleared
     * on user-initiated disconnect so we don't try to reconnect after
     * an explicit teardown.
     */
    /**
     * Captured connect arguments + the camera MAC we last paired with.
     * Auto-reconnect uses the MAC to look up the persisted profile and
     * pass it as `preferredProfile`, which lets the connect flow skip
     * discovery and head straight to InitCommand against the cached IP.
     * Recovers from a mid-session Broken-pipe in ~3 s instead of the
     * 1–40 s discovery cost.
     */
    private data class LastConnectArgs(
        val cameraIp: String,
        val hostName: String,
        /** MAC of the camera we paired with last time, populated by the
         *  successful-connect block. Null on the very first attempt. */
        val cameraMac: String?,
    )

    private var lastConnectArgs: LastConnectArgs? = null

    /**
     * Serializes all entries into [connect]. Without this, the
     * combination of {user-tap, auto-reconnect supervisor, deep-link
     * intent} could enter the function concurrently and race past the
     * state-check at the top — observed in early logcat dumps as 50+
     * `WrongNetwork` Failed transitions within 8 s. The mutex turns
     * concurrent calls into a queue; the first holder runs to
     * completion, subsequent holders see `state == Connected` and
     * short-circuit.
     */
    private val connectMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Auto-reconnect supervisor — fires when the connection transitions
     * to Failed/Disconnected while [lastConnectArgs] is still set (i.e.
     * the user did NOT tap Disconnect). Walks an exponential backoff
     * from 2 s up to 30 s.
     */
    private var autoReconnectJob: Job? = null

    /**
     * True while [runAutoReconnectLoop] is actively trying to re-establish a
     * dropped session. Keeps the foreground service (and its
     * `WIFI_MODE_FULL_HIGH_PERF` WifiLock + heartbeat) alive ACROSS the
     * transient Failed/Connecting churn of a reconnect, instead of tearing it
     * down the instant the session drops — which would put the Wi-Fi radio
     * into power-save exactly when we need it hot to re-pair.
     */
    private val _reconnecting = MutableStateFlow(false)

    private val _captureEvents = MutableStateFlow<CaptureEvent?>(null)
    val latestCaptureEvent: StateFlow<CaptureEvent?> = _captureEvents.asStateFlow()

    init {
        // Hydrate from DataStore on first construction. The Singleton lifetime
        // means this runs exactly once per process; emissions after the
        // initial replay just keep the in-memory mirror current if some other
        // call site writes to DataStore directly (none today, but cheap
        // insurance).
        preferences.armedFolderUri
            .onEach { uri -> _armedFolderUri.value = uri }
            .launchIn(sessionScope)
        preferences.formatMode
            .onEach { mode -> _formatMode.value = mode }
            .launchIn(sessionScope)

        // Long-running supervisor — fires the foreground service on/off as the
        // connection state transitions. We start the FGS as soon as the camera
        // session is live (NOT only when armed) because:
        //   1) WIFI_MODE_FULL_HIGH_PERF Wi-Fi lock must be held the whole
        //      session, not just during transfers, to defeat Android's
        //      power-save mode that triggers latency spikes + AP drops.
        //   2) The PCHDDCapacity heartbeat (started inside the repository
        //      session scope) needs the process at FOREGROUND oom_adj so
        //      Doze / Low-Memory Killer can't pause its coroutine.
        //   3) Manufacturer battery savers (MIUI / Honor / Oppo) ignore
        //      software locks when the app is backgrounded; the FGS is
        //      the only signal Android honours to keep the radio hot.
        // The service self-stops when it observes connection == Disconnected
        // via its own reconcile() loop, so this drives only the ramp-up.
        combine(_connectionState, _reconnecting) { state, reconnecting ->
            // Keep the FGS (WifiLock + heartbeat) up while Connected, AND
            // through the transient Failed/Connecting states WHILE an
            // auto-reconnect is in flight. Only release once the session is
            // truly Connected-less: user disconnect (Disconnected) or the
            // reconnect loop giving up (_reconnecting flips false, leaving a
            // terminal Failed).
            state == ConnectionState.Connected ||
                (reconnecting &&
                    (state == ConnectionState.Failed || state == ConnectionState.Connecting))
        }
            .distinctUntilChanged()
            .onEach { shouldRun ->
                if (shouldRun) CanonSyncCaptureService.start(context)
                else CanonSyncCaptureService.stop(context)
            }
            .launchIn(sessionScope)

        // Auto-reconnect supervisor: only stops a running reconnect loop
        // when the connection transitions to Connecting/Connected. We
        // intentionally DON'T spawn new reconnect loops from state
        // transitions — that was previously causing parallel-loop blowup
        // because each connect() failure flipped state to Failed AGAIN
        // INSIDE the running loop, which then scheduled another loop on
        // top of itself. Reconnect requests now come from exactly one
        // place: HeartbeatLoop.onSessionDeadDetected → scheduleAutoReconnect.
        _connectionState
            .onEach { state ->
                if (state == ConnectionState.Connecting ||
                    state == ConnectionState.Connected
                ) {
                    autoReconnectJob?.cancel()
                    autoReconnectJob = null
                }
            }
            .launchIn(sessionScope)
    }

    fun arm(folderUri: Uri) {
        // Optimistically update the in-memory mirror so the UI reacts
        // synchronously; DataStore replay would otherwise add a frame of
        // latency. The persist-back is launched on sessionScope and the
        // DataStore-collector flow above will keep things consistent if the
        // write fails for any reason.
        _armedFolderUri.value = folderUri
        sessionScope.launch { preferences.setArmedFolderUri(folderUri) }
        // 6D-class bodies only emit 0xC181 EOS_ObjectAddedEx when the
        // shot is routed to the host (D11C=3 Both or 4 Host-RAM). With
        // the factory default D11C=1 (Card only) the camera saves the
        // photo but never tells us about it, so the CaptureCoordinator
        // sits silent. Push D11C=3 so the card still gets the original
        // and the host gets notified for auto-download.
        applyArmedCaptureDestination()
    }

    fun disarm() {
        _armedFolderUri.value = null
        sessionScope.launch { preferences.setArmedFolderUri(null) }
        // Revert to factory default so a user who unarmed isn't left
        // with the camera dumping every shot into RAM for no reason.
        applyDisarmedCaptureDestination()
    }

    /**
     * Switch the camera to Both (Card + Host) so shutter events fire
     * the 0xC181 EOS_ObjectAddedEx record the CaptureCoordinator listens
     * for. No-op when the session is down — when it comes back up,
     * [rebuildCoordinatorIfArmed] will apply this again.
     */
    private fun applyArmedCaptureDestination() = pushCaptureDestination(
        value = PtpIpConstants.DPC_CAPTURE_DEST_BOTH,
        reason = "armed",
    )

    /** Revert the camera to Card-only after disarm. */
    private fun applyDisarmedCaptureDestination() = pushCaptureDestination(
        value = PtpIpConstants.DPC_CAPTURE_DEST_CARD,
        reason = "disarmed",
    )

    /**
     * Shared body for [applyArmedCaptureDestination] and
     * [applyDisarmedCaptureDestination]. Routes through [launchOnClient]
     * so it's a no-op when the session is down (the next reconnect's
     * [rebuildCoordinatorIfArmed] re-applies the value).
     */
    private fun pushCaptureDestination(value: Int, reason: String) = launchOnClient {
        val ok = it.setCaptureDestination(value)
        Log.i(TAG, "pushCaptureDestination: D11C=$value reason=$reason ok=$ok")
    }

    /**
     * Enumerate everything on the camera's SD card (standard-PTP
     * [GetStorageIDs → GetObjectHandles → GetObjectInfo]) and update
     * [cameraObjects]. Throws nothing — populates an empty list on
     * any failure so the UI can render an "empty / retry" state.
     *
     * Runs on a background coroutine; suspend-free public entry point
     * matches the rest of the repository's UI-side surface.
     */
    fun refreshCameraObjects() {
        val activeClient = client ?: return
        sessionScope.launch {
            _cameraBrowserBusy.value = true
            try {
                _cameraObjects.value = activeClient.listCameraObjects()
                Log.i(TAG, "refreshCameraObjects: ${_cameraObjects.value.size} entries")
            } catch (t: Throwable) {
                Log.w(TAG, "refreshCameraObjects: failed", t)
                _cameraObjects.value = emptyList()
            } finally {
                _cameraBrowserBusy.value = false
            }
        }
    }

    /**
     * Pair RAW + JPEG handles into [CameraPhoto] tiles, filtered by the
     * user's [FormatMode] setting.
     *
     * The 6D in RAW+JPEG mode writes each shot as a pair of object
     * handles that differ by exactly 1 in the low nibble:
     * `0x9190fb61` (CR2) + `0x9190fb62` (JPG). We sort the raw list
     * by handle value and walk it in pairs — when two consecutive
     * entries are adjacent (`b.handle == a.handle + 1`), bundle them.
     * Stragglers (camera was in single-format mode) get their own
     * tile with only the matching handle filled in.
     *
     * Filtering:
     *  - [FormatMode.JPEG]    → only photos that have a JPG sibling
     *  - [FormatMode.RAW]     → only photos that have a RAW sibling
     *  - [FormatMode.RAW_AND_JPEG] → all photos
     */
    val cameraPhotos: StateFlow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>> =
        combine(_cameraObjects, _formatMode, _sortMode) { objects, mode, sort ->
            sortPhotos(pairCameraObjects(objects, mode), sort)
        }.stateIn(
            scope = sessionScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    /** Short uppercase format token (CR2/CR3/JPG/ARW/NEF/DNG/…) for the tile badge. */
    private fun formatLabelOf(
        obj: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo,
    ): String {
        val ext = obj.filename.substringAfterLast('.', "").uppercase()
        if (ext.length in 2..4) return when (ext) {
            "JPEG" -> "JPG"; "TIFF" -> "TIF"; else -> ext
        }
        return when (obj.format) {
            com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants.FMT_JPEG -> "JPG"
            com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants.FMT_CANON_CR2 -> "CR2"
            com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants.FMT_CANON_CR3 -> "CR3"
            // 6D EOS-Utility mode exposes no format/filename — infer from the
            // handle parity the pairing relies on (odd = RAW sibling).
            else -> if ((obj.handle and 1) == 1) "RAW" else "JPG"
        }
    }

    /**
     * Re-order paired photos by the user's [BrowserSortMode]. The base list from
     * [pairCameraObjects] is already newest-first (capture order); every sort
     * here is STABLE so within a group (same format/ISO/…) that newest-first
     * order is preserved. EXIF keys (ISO/Aperture/Lens) push not-yet-loaded
     * photos to the end via a MAX sentinel.
     */
    private fun sortPhotos(
        photos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
        sort: BrowserSortMode,
    ): List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto> = when (sort) {
        BrowserSortMode.DATE_NEWEST -> photos
        BrowserSortMode.DATE_OLDEST -> photos.reversed()
        BrowserSortMode.FORMAT -> photos.sortedBy { it.formatLabel }
        BrowserSortMode.ISO -> photos.sortedBy { it.isoValue ?: Int.MAX_VALUE }
        BrowserSortMode.APERTURE -> photos.sortedBy { it.apertureF ?: Float.MAX_VALUE }
        BrowserSortMode.LENS -> photos.sortedBy { it.lensModel ?: "￿" }
    }

    private fun pairCameraObjects(
        raw: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo>,
        mode: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode,
    ): List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto> {
        if (raw.isEmpty()) return emptyList()
        // Sort by unsigned handle value. Canon EOS handles use the high
        // bit (0x9xxxxxxx), so an unsigned sort matches the camera's
        // own enumeration order — which is what makes adjacent pairing
        // reliable.
        val sorted = raw.sortedBy { it.handle.toLong() and 0xFFFFFFFFL }
        val out = mutableListOf<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>()
        var i = 0
        while (i < sorted.size) {
            val cur = sorted[i]
            val next = sorted.getOrNull(i + 1)
            // A pair is two consecutive handles differing by exactly 1,
            // where the lower one ends in an odd hex digit (RAW) and
            // the upper ends in even (JPEG). Verified on 6D: pairs
            // are always (0xN1, 0xN2) or (0xN3, 0xN4) etc — the low
            // nibble of the lower handle is odd.
            val isPair = next != null &&
                next.handle.toLong() - cur.handle.toLong() == 1L &&
                (cur.handle and 1) == 1
            val photo = if (isPair) {
                i += 2
                com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto(
                    displayName = synthesizePhotoName(cur.handle),
                    rawHandle = cur.handle,
                    jpegHandle = next!!.handle,
                    storageId = cur.storageId,
                    formatLabel = formatLabelOf(cur),
                )
            } else {
                i += 1
                // Singleton — guess format from the low nibble. Odd = RAW.
                if ((cur.handle and 1) == 1) {
                    com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto(
                        displayName = synthesizePhotoName(cur.handle),
                        rawHandle = cur.handle,
                        jpegHandle = null,
                        storageId = cur.storageId,
                        formatLabel = formatLabelOf(cur),
                    )
                } else {
                    com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto(
                        displayName = synthesizePhotoName(cur.handle),
                        rawHandle = null,
                        jpegHandle = cur.handle,
                        storageId = cur.storageId,
                        formatLabel = formatLabelOf(cur),
                    )
                }
            }
            // Filter according to the user's FormatMode setting.
            val include = when (mode) {
                com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode.RAW ->
                    photo.hasRaw
                com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode.JPEG ->
                    photo.hasJpeg
                com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode.RAW_AND_JPEG ->
                    true
            }
            if (include) out += photo
        }
        // Newest first: Canon assigns object handles in capture order (higher
        // handle = more recent shot), so an unsigned-DESCENDING handle sort puts
        // the most recently taken photos on top of the SD-card browser. (The
        // pairing above needs the ascending sort; this re-orders the finished
        // tiles. ObjectInfo carries no capture-date field, so the handle is the
        // reliable capture-order proxy on EOS bodies.)
        out.sortByDescending {
            (it.rawHandle ?: it.jpegHandle ?: 0).toLong() and 0xFFFFFFFFL
        }
        return out
    }

    /**
     * Synthesise an `IMG_xxxx` label from a handle. The handle's mid
     * bytes (after stripping the high `0x9190` prefix the 6D uses)
     * roughly track the camera's own counter, so this stays consistent
     * with what the user sees on the camera body.
     */
    private fun synthesizePhotoName(handle: Int): String =
        "IMG_${"%04d".format((handle ushr 4) and 0xFFFF)}"

    /**
     * Download a single image from the camera (chunked 0x9107) into
     * the session output folder under
     * `{outputRoot}/{modelName}-{yyyyMMdd}/{filename}`. The session
     * folder is created if missing; existing-name collisions get the
     * `_1`/`_2` suffix treatment already implemented in
     * [com.RAZStudio.StudioRoom.feature.canon_sync.data.SafCaptureTarget].
     *
     * Caller-side state: [cameraBrowserBusy] flips true for the duration
     * so the UI can disable other taps while a download is in flight.
     */
    /**
     * Active preview state for the Browse canvas. Holds the in-flight or
     * completed download of a single camera-side photo, intended to be
     * displayed in a full-screen popup with Edit/Download buttons.
     *
     * Lifecycle:
     *   - [previewCameraObject] flips it to [PreviewState.Loading].
     *   - On success, becomes [PreviewState.Ready] with the cached file path.
     *   - On failure / dismiss, becomes [PreviewState.Idle].
     *
     * Cached files live in `cacheDir/canon-preview/`. We don't bother
     * pruning per-photo — the OS will reclaim the cache dir under
     * pressure, and the average session loads a handful of photos.
     */
    sealed interface PreviewState {
        data object Idle : PreviewState
        data class Loading(val handle: Int, val filename: String) : PreviewState
        data class Ready(
            val handle: Int,
            val filename: String,
            val cachedFile: java.io.File,
        ) : PreviewState
    }

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    /**
     * Bounded LRU cache of full-resolution preview files, keyed by photo
     * handle. Sized for `current + 2 left + 2 right = 5` entries — when
     * the user swipes through the photo strip we keep the immediate
     * neighbours warm so taps feel instant. Anything 3+ positions away
     * from the current viewport gets evicted (file deleted, entry
     * removed) so we don't unboundedly fill cacheDir.
     *
     * Access pattern is "touch on read", so [LinkedHashMap] with
     * accessOrder=true gives true LRU semantics for free. All mutations
     * happen under [previewCacheLock].
     *
     * Lifetime: cleared by [wipeSessionCaches] on user-initiated
     * disconnect or session-loss teardown.
     */
    private val previewCacheLock = Any()
    private val previewCache = object : LinkedHashMap<Int, java.io.File>(
        /* initialCapacity = */ PREVIEW_CACHE_CAPACITY * 2,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        // NOTE: eviction does NOT delete the underlying file. The file
        // stays on disk under `cacheDir/canon-preview/` until the
        // session ends ([wipeSessionCaches] cleans the whole dir). This
        // makes re-visits cheap: a user paging back past the LRU window
        // hits [streamPreviewToCache]'s on-disk fallback and re-adopts
        // the file without another download. The total cacheDir
        // footprint is still bounded — we cap on-disk preview files at
        // the manifest size and the OS reclaims cacheDir under
        // memory pressure.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, java.io.File>): Boolean {
            val tooBig = size > PREVIEW_CACHE_CAPACITY
            if (tooBig) {
                Log.d(TAG, "previewCache: evicted handle=0x${"%08x".format(eldest.key)} " +
                    "from in-memory map (file kept on disk for adoption)")
            }
            return tooBig
        }
    }

    /**
     * Stream a single camera-side photo into the local cache dir for
     * preview in the Browse canvas. Updates [previewState] as the
     * download progresses.
     *
     * The 6D has no fast thumbnail/preview opcode that works in EOS
     * Utility mode — both `GetThumb (0x100A)` and `EOS_GetObjectInfoEx`
     * are silently ignored on image handles. So this streams the FULL
     * file (typically 6 MB JPG / 25 MB CR2) over the camera AP. That's
     * 5–15 s per tap; acceptable per the user spec.
     */
    fun previewCameraObject(
        photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto,
    ) {
        // For preview canvas: prefer the JPG sibling for a full download
        // — it's the same photo as the RAW, just already in a
        // decodable format. Multi-MB but acceptable per the user spec
        // for tap-to-view. For RAW-only shots fall back to streaming
        // the CR2 (BitmapFactory can decode the embedded preview JPEG
        // out of the TIFF wrapper via decodePhotoFile in the UI).
        val handle = photo.jpegHandle ?: photo.rawHandle ?: return
        val isJpeg = photo.jpegHandle != null
        val ext = if (isJpeg) "JPG" else "CR2"
        val displayName = "${photo.displayName}.$ext"
        // Cache hit fast path: a previous open / prefetch already pulled
        // this photo. Skip the multi-second download entirely.
        val cached = synchronized(previewCacheLock) { previewCache[handle] }
        if (cached != null && cached.exists() && cached.length() > 0) {
            Log.i(TAG, "previewCameraObject: cache HIT '$displayName' (${cached.length()} B)")
            _previewState.value = PreviewState.Ready(handle, displayName, cached)
            return
        }
        sessionScope.launch {
            _previewState.value = PreviewState.Loading(handle, displayName)
            val file = streamPreviewToCache(handle, displayName)
            _previewState.value = if (file != null) {
                PreviewState.Ready(handle, displayName, file)
            } else {
                PreviewState.Idle
            }
        }
    }

    /**
     * Background preload for the photos immediately adjacent to whatever
     * the user is currently viewing in the swipe-through canvas. Each
     * call schedules ±[PREVIEW_NEIGHBOR_WINDOW] siblings of [around];
     * the LRU cache evicts anything beyond ±3 positions automatically.
     *
     * Multiple in-flight prefetches are gated by [prefetchInFlight] —
     * if the user swipes fast we just keep the latest target, no
     * cancel-and-restart needed (the bandwidth wasn't wasted, the file
     * is already in cache).
     *
     * Idempotent: photos already in [previewCache] are skipped.
     */
    /**
     * Per-handle in-flight tracker. Foreground (`previewCameraObject`)
     * and background prefetches both register here so we never start a
     * duplicate download for the same handle. Callers that arrive while
     * a fetch is in flight `await()` the existing Deferred instead of
     * spinning up a second one — observed in adb to be a hot path
     * during pager swipes, where a foreground tap and a neighbour
     * prefetch can both target the same handle within milliseconds.
     */
    private val inFlightDownloads = mutableMapOf<Int, kotlinx.coroutines.CompletableDeferred<java.io.File?>>()
    fun prefetchPreviewNeighbors(
        ordered: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
        currentIndex: Int,
    ) {
        if (ordered.isEmpty()) return
        val window = PREVIEW_NEIGHBOR_WINDOW
        val targets = (currentIndex - window..currentIndex + window)
            .filter { it in ordered.indices && it != currentIndex }
            .map { ordered[it] }
        for (photo in targets) {
            val h = photo.jpegHandle ?: photo.rawHandle ?: continue
            val isJpeg = photo.jpegHandle != null
            val displayName = "${photo.displayName}.${if (isJpeg) "JPG" else "CR2"}"
            val have = synchronized(previewCacheLock) { previewCache[h] }
            if (have != null && have.exists() && have.length() > 0) continue
            // `streamPreviewToCache` coalesces concurrent calls for the
            // same handle via [inFlightDownloads], so it's safe to fire
            // here even if a foreground tap is about to also kick a
            // fetch for the same photo: the second caller awaits the
            // first's CompletableDeferred and they both see the same
            // file at the end.
            sessionScope.launch {
                streamPreviewToCache(h, displayName)
            }
        }
    }

    /**
     * Shared cache-fill body for [previewCameraObject] (foreground, sets
     * [PreviewState]) and [prefetchPreviewNeighbors] (background, just
     * fills the LRU). Returns the cached file on success or null on
     * any failure. Wires the result into [previewCache] and bumps its
     * LRU position.
     */
    private suspend fun streamPreviewToCache(
        handle: Int,
        displayName: String,
    ): java.io.File? {
        val activeClient = client ?: return null
        val cacheDir = java.io.File(context.cacheDir, "canon-preview").apply { mkdirs() }
        val cacheFile = java.io.File(cacheDir, "${"%08x".format(handle)}-$displayName")

        // ── SAF-folder adoption (browse-canvas + manual download reuse) ──
        // Before initiating a multi-MB transfer, see if the photo is
        // already sitting in the user's working folder (or its
        // session-subfolder). The Download / Edit flows write files
        // there, so a user who downloaded a shot earlier shouldn't pay
        // the transfer cost again to preview it. Adoption = copy into
        // cacheDir so the downstream pipeline (which expects a File)
        // continues to work unmodified.
        val expectedSizeForAdopt: Long = _cameraObjects.value
            .firstOrNull { it.handle == handle }?.sizeBytes
            ?: 0L
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val workDirStr = settingsManager.settingsState.value.saveFolderUri
            val workDir: Uri? = workDirStr
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (workDir != null) {
                val root = runCatching {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, workDir)
                }.getOrNull()
                if (root != null && root.isDirectory) {
                    // Search root + one level deep (session subfolders).
                    fun matchIn(dir: androidx.documentfile.provider.DocumentFile?):
                        androidx.documentfile.provider.DocumentFile? {
                        if (dir == null || !dir.isDirectory) return null
                        return dir.findFile(displayName)
                            ?.takeIf { it.isFile && it.length() > 0 }
                    }
                    val found = matchIn(root)
                        ?: root.listFiles().asSequence()
                            .filter { it.isDirectory }
                            .mapNotNull { matchIn(it) }
                            .firstOrNull()
                    if (found != null &&
                        (expectedSizeForAdopt <= 0 || found.length() == expectedSizeForAdopt)
                    ) {
                        val copied = runCatching {
                            context.contentResolver.openInputStream(found.uri)?.use { input ->
                                cacheFile.outputStream().use { out -> input.copyTo(out) }
                            }
                            cacheFile.takeIf { it.exists() && it.length() > 0 }
                        }.getOrNull()
                        if (copied != null) {
                            synchronized(previewCacheLock) {
                                previewCache[handle] = copied
                            }
                            Log.i(TAG, "streamPreviewToCache: adopted '$displayName' " +
                                "(${copied.length()} B) from working folder — no download")
                            return copied
                        }
                    }
                }
            }
        }

        // ── Single-flight coalescing + cache hits ────────────────────────
        // Decide our role atomically under previewCacheLock. We encode
        // the role as a (kind, payload) Pair instead of a sealed
        // interface because Kotlin disallows local sealed declarations.
        //   ROLE_MEMORY  → existing LRU entry, return immediately
        //   ROLE_DISK    → on-disk file from an earlier session; re-adopt
        //   ROLE_ATTACH  → another caller is already downloading this
        //                  handle; await their CompletableDeferred
        //   ROLE_OWN     → first-mover for this handle; we publish a
        //                  CompletableDeferred so future callers attach
        val roleMemory = 1; val roleDisk = 2; val roleAttach = 3; val roleOwn = 4
        // Expected size from the manifest (if we have it). Adopt-on-disk
        // is only safe when the file's length matches; otherwise it's a
        // partial download from a prior session that died mid-transfer
        // (Broken pipe), and adopting it would render a truncated /
        // corrupted photo. Without an expected size (e.g. SD card
        // manifest hasn't loaded yet) we fall back to "adopt anything
        // non-empty" — the user's only alternative is a re-download
        // which is what we'd do anyway.
        val expectedSize: Long = _cameraObjects.value
            .firstOrNull { it.handle == handle }?.sizeBytes
            ?: 0L
        val rolePair: Pair<Int, Any> = synchronized(previewCacheLock) {
            val existing = previewCache[handle]
            if (existing != null && existing.exists() && existing.length() > 0 &&
                (expectedSize <= 0 || existing.length() == expectedSize)
            ) {
                return@synchronized roleMemory to existing
            }
            if (cacheFile.exists() && cacheFile.length() > 0 &&
                (expectedSize <= 0 || cacheFile.length() == expectedSize)
            ) {
                previewCache[handle] = cacheFile
                return@synchronized roleDisk to cacheFile
            }
            // Critical: check in-flight BEFORE the "stale partial" delete
            // path. A concurrent OWNER has already opened a BufferedSink
            // to `cacheFile`, which created a 0-byte file on disk. If we
            // delete that file here, the owner's sink writes into an
            // unlinked inode (which on Android leaves the directory
            // entry empty), and then the owner's own length-check
            // declares "0 bytes success" and deletes again. End result:
            // every prefetch+foreground race wastes a download. Coalesce
            // first — the attached caller gets the OWNER's eventual
            // result, no on-disk surgery needed.
            val pending = inFlightDownloads[handle]
            if (pending != null) {
                return@synchronized roleAttach to pending
            }
            // No active owner. Now it's safe to clean up stale partials
            // — they're a leftover from a prior session that exited
            // mid-download. Only delete files whose length disagrees
            // with the manifest; ignore 0-byte ghosts because they
            // might have been left by a sink-open that didn't write
            // anything (we don't trust their state).
            if (cacheFile.exists() && expectedSize > 0 && cacheFile.length() != expectedSize) {
                Log.w(TAG, "streamPreviewToCache: stale on-disk '$displayName' " +
                    "(${cacheFile.length()} B, expected $expectedSize B) — deleting")
                cacheFile.delete()
                previewCache.remove(handle)
            }
            val d = kotlinx.coroutines.CompletableDeferred<java.io.File?>()
            inFlightDownloads[handle] = d
            roleOwn to d
        }
        when (rolePair.first) {
            roleMemory -> return rolePair.second as java.io.File
            roleDisk -> {
                val f = rolePair.second as java.io.File
                Log.i(TAG, "streamPreviewToCache: re-adopting on-disk '$displayName' " +
                    "(${f.length()} B) — skipping download")
                return f
            }
            roleAttach -> {
                Log.d(TAG, "streamPreviewToCache: coalescing into in-flight '$displayName'")
                @Suppress("UNCHECKED_CAST")
                return (rolePair.second as kotlinx.coroutines.CompletableDeferred<java.io.File?>).await()
            }
        }
        @Suppress("UNCHECKED_CAST")
        val ownDeferred = rolePair.second as kotlinx.coroutines.CompletableDeferred<java.io.File?>
        // Helper that drops us from the in-flight map and publishes the
        // result to every attached caller. Always called exactly once
        // before this function returns — wrapped in runCatching so an
        // already-completed deferred (defensive) doesn't throw.
        val finish: (java.io.File?) -> Unit = { result ->
            synchronized(previewCacheLock) {
                // Only remove ourselves if we're still the registered
                // owner — wipeSessionCaches can clear the map under us.
                if (inFlightDownloads[handle] === ownDeferred) {
                    inFlightDownloads.remove(handle)
                }
            }
            runCatching { ownDeferred.complete(result) }
        }
        val sink: okio.BufferedSink = runCatching { cacheFile.sink().buffer() }
            .getOrNull() ?: run {
            Log.w(TAG, "streamPreviewToCache: could not open sink for '$displayName'")
            finish(null)
            return null
        }
        val outcome: java.io.File? = try {
            val result = activeClient.streamObjectViaPartial(
                handle = handle,
                destination = sink,
            )
            sink.flush()
            sink.close()
            if (result is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult.Success) {
                // 0-byte "success" — observed in adb when the camera
                // acks the transfer but sends no payload (mid-session
                // socket hiccup, partial close). Treat as failure;
                // accepting it would put a black file into the LRU
                // that decodes to null and serves a black canvas on
                // every future visit.
                if (cacheFile.length() <= 0) {
                    Log.w(TAG, "streamPreviewToCache: SUCCESS but 0 bytes for '$displayName' " +
                        "— treating as failure, deleting")
                    cacheFile.delete()
                    null
                } else {
                    Log.i(TAG, "streamPreviewToCache: SUCCESS '$displayName' (${cacheFile.length()} B)")
                    synchronized(previewCacheLock) {
                        previewCache[handle] = cacheFile
                    }
                    cacheFile
                }
            } else {
                Log.w(TAG, "streamPreviewToCache: $result for '$displayName'")
                cacheFile.delete()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "streamPreviewToCache: threw", t)
            runCatching { sink.close() }
            cacheFile.delete()
            null
        }
        finish(outcome)
        return outcome
    }

    /**
     * Wipe every per-session cache: grid thumbs (small), preview files
     * (full-resolution LRU), the on-disk cache directories that back
     * them, and the camera-object manifest. Invoked from [disconnect]
     * (user intent) and [tearDownSession] when the session ends. After
     * this returns the user is effectively back to the "no camera
     * paired" state from the cache's POV — next reconnect will re-fetch.
     */
    private fun wipeSessionCaches() {
        Log.i(TAG, "wipeSessionCaches: clearing thumbs + previews")
        // Grid thumbs: cancel any in-flight batch, drop the StateFlow
        // map, forget the handle-set so a future browser opens at zero,
        // and wipe the on-disk cache dir.
        cancelGridThumbsJob()
        lastGridThumbsHandles = emptySet()
        _gridThumbs.value = emptyMap()
        runCatching {
            java.io.File(context.cacheDir, "canon-thumb").listFiles()
                ?.forEach { it.delete() }
        }
        // Preview LRU: drop in-memory map, delete every staged file,
        // wipe the on-disk dir for orphans.
        synchronized(previewCacheLock) {
            for (entry in previewCache.values) {
                runCatching { entry.delete() }
            }
            previewCache.clear()
            // Fail any in-flight coalesced waits so attached callers
            // unblock with null instead of hanging forever.
            for ((_, deferred) in inFlightDownloads) {
                runCatching { deferred.complete(null) }
            }
            inFlightDownloads.clear()
        }
        runCatching {
            java.io.File(context.cacheDir, "canon-preview").listFiles()
                ?.forEach { it.delete() }
        }
        // Camera manifest — without this the next session would start
        // with stale handles that don't exist on the new camera (or
        // belong to a different storage volume).
        _cameraObjects.value = emptyList()
        _previewState.value = PreviewState.Idle
        // Live-view stream — kill the polling coroutine and reset state
        // so a fresh session doesn't inherit a stale frame.
        liveViewJob?.cancel()
        liveViewJob = null
        _liveViewState.value = LiveViewState.Idle
        _captureInProgress.value = false
    }

    /**
     * Cache of small thumbnail files keyed by photo handle. Each entry
     * is a ~16 KB EXIF-embedded JPEG fetched lazily after the
     * [cameraPhotos] list lands. The grid tiles observe this map and
     * render a bitmap as each handle's thumb file appears.
     *
     * Files live in `cacheDir/canon-thumb/{handle:08x}.jpg`. The OS
     * reclaims cacheDir under memory pressure; no per-photo eviction
     * needed.
     */
    private val _gridThumbs = MutableStateFlow<Map<Int, java.io.File>>(emptyMap())
    val gridThumbs: StateFlow<Map<Int, java.io.File>> = _gridThumbs.asStateFlow()

    private var gridThumbsJob: kotlinx.coroutines.Job? = null

    /** Handles currently visible in the SD-card browser grid. Used to prioritize thumb fetches. */
    private val _visibleGridHandles = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Set of handles for which the LAST grid-thumb batch was scheduled.
     * Stops the LaunchedEffect from restarting the batch every time
     * the photo list flow re-emits an equal-but-not-identical list.
     */
    private var lastGridThumbsHandles: Set<Int> = emptySet()

    /**
     * Called by the UI whenever the set of visible grid tiles changes (on
     * scroll). If a fetch batch is already running, it is restarted so the
     * newly-visible handles jump to the front of the queue (already-cached
     * handles are skipped as usual).
     */
    fun updateVisibleGridHandles(
        handles: Set<Int>,
        photos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
    ) {
        if (_visibleGridHandles.value == handles) return
        _visibleGridHandles.value = handles
        // Restart the batch so visible handles are fetched first. The restart
        // short-circuits immediately for anything already cached.
        if (gridThumbsJob?.isActive == true) {
            startGridThumbsFetch(photos, restartForVisibility = true)
        }
    }

    fun startGridThumbsFetch(
        photos: List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto>,
        restartForVisibility: Boolean = false,
    ) {
        val handles = photos.map { it.previewHandle }.toSet()
        // No-op if the set we're about to fetch matches the LAST handle
        // set AND we already have at least all the thumb files in
        // `_gridThumbs` for them — i.e. the fetch is a re-entry from a
        // re-opened browser, all data is already cached, nothing to do.
        val haveAllCached = handles.isNotEmpty() &&
            handles.all { _gridThumbs.value.containsKey(it) }
        if (!restartForVisibility && handles == lastGridThumbsHandles &&
            (gridThumbsJob?.isActive == true || haveAllCached)
        ) {
            Log.d(TAG, "startGridThumbsFetch: same handles, ${if (haveAllCached) "all cached" else "batch in flight"} — skipping")
            return
        }
        cancelGridThumbsJob()
        lastGridThumbsHandles = handles
        val activeClient = client
        if (activeClient == null) {
            Log.w(TAG, "startGridThumbsFetch: no active client, aborting")
            return
        }
        // Sort so currently-visible handles come first, then the rest in
        // original order. This ensures the first ~6 tiles the user sees are
        // populated immediately, rather than waiting for all photos before them
        // in the list to finish downloading.
        val visible = _visibleGridHandles.value
        val prioritized = if (visible.isEmpty()) {
            photos
        } else {
            val visiblePhotos = photos.filter { it.previewHandle in visible }
            val rest = photos.filter { it.previewHandle !in visible }
            visiblePhotos + rest
        }
        Log.i(TAG, "startGridThumbsFetch: scheduling ${photos.size} thumbs " +
            "(cached=${_gridThumbs.value.size}, visible-first=${visible.size})")
        val cacheDir = java.io.File(context.cacheDir, "canon-thumb").apply { mkdirs() }
        // Keep already-fetched thumbs across browser-dialog dismiss / reopen
        // and across filter changes — the grid map is invalidated only when
        // the session ends (see [wipeSessionCaches]). Stale files on disk
        // for handles we DO have keep the bitmap renderer happy; orphans
        // beyond this session are wiped at disconnect time.
        gridThumbsJob = sessionScope.launch {
            // Prime the thumbnail cache once per unique (storage, parent)
            // pair — EOS Utility wire trace shows this gates per-photo
            // 0x910A responses.
            val primed = mutableSetOf<Pair<Int, Int>>()
            for (photo in prioritized) {
                if (!isActive) break
                val handle = photo.previewHandle
                val cached = _gridThumbs.value[handle]
                if (cached != null) continue
                val parentFolder = handle and 0xFFFF0000.toInt()
                val key = photo.storageId to parentFolder
                if (key !in primed) {
                    val primeOutcome = runCatching {
                        activeClient.primeThumbCache(
                            storageId = photo.storageId,
                            parentFolder = parentFolder,
                        )
                    }
                    // If the prime failed with a socket-dead error
                    // (Broken pipe / EOF), the underlying TCP is gone
                    // and every subsequent per-photo fetchThumb will
                    // also fail. The heartbeat will declare the session
                    // dead within ~9 s and trigger auto-reconnect, but
                    // there's no point burning round-trips against a
                    // dead socket in the meantime — bail the whole
                    // batch and let it re-run after reconnect.
                    val cause = primeOutcome.exceptionOrNull()
                    if (cause != null && isSocketDeadException(cause)) {
                        Log.w(TAG, "startGridThumbsFetch: socket dead " +
                            "(${cause.javaClass.simpleName}: ${cause.message}) — aborting batch")
                        break
                    }
                    primed += key
                }
                val buf = runCatching {
                    activeClient.fetchThumb(handle = handle)
                }.getOrNull()
                if (buf == null || buf.size <= 0) continue
                val file = java.io.File(cacheDir, "${"%08x".format(handle)}.jpg")
                runCatching {
                    file.sink().buffer().use { sink ->
                        sink.writeAll(buf)
                        // The 6D's 0x910A response can end mid-XMP without
                        // a JPEG EOI marker (FF D9). Android's BitmapFactory
                        // is strict and returns null on EOI-less JPEGs even
                        // when the EXIF preview at the front is fully decodable.
                        // Append a synthetic EOI so the decoder accepts it.
                        sink.writeByte(0xFF)
                        sink.writeByte(0xD9)
                        sink.flush()
                    }
                }.onSuccess {
                    _gridThumbs.update { current ->
                        current + (handle to file)
                    }
                }
            }
        }
    }

    fun cancelGridThumbsJob() {
        gridThumbsJob?.cancel()
        gridThumbsJob = null
    }

    /** Dismiss the preview canvas (user tapped close / back). */
    fun dismissPreview() {
        _previewState.value = PreviewState.Idle
    }

    // ───────────────── Live Remote Shooting actions ───────────────────
    /**
     * Start the live-view stream. Writes the EVF setup DPCs, then
     * spawns a long-running coroutine that polls
     * [CanonWifiClient.fetchViewFinderFrame] back-to-back, decodes
     * the Canon envelope to extract the embedded JPEG, and pushes
     * the bytes into [liveViewState]'s [LiveViewState.Streaming].
     *
     * Idempotent — second call is a no-op if a stream is already
     * running. Cancelled by [stopLiveView] or session teardown.
     */
    fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        val activeClient = client ?: run {
            _liveViewState.value = LiveViewState.Error("Camera not connected")
            return
        }
        _liveViewState.value = LiveViewState.Starting
        liveViewJob = sessionScope.launch {
            // ALL camera I/O wrapped in defensive try/catch. A
            // Broken-pipe mid-write would otherwise propagate up the
            // coroutine and crash the app via Android's uncaught-
            // exception handler. Any wire exception here means the
            // session died — surface as Error and let the heartbeat
            // auto-reconnect supervisor take over.
            try {
                val started = runCatching { activeClient.startViewFinder() }
                    .getOrNull() == true
                if (!started) {
                    _liveViewState.value = LiveViewState.Error(
                        "Camera refused live-view — try reconnect"
                    )
                    return@launch
                }
                // Warm-up loop with escalating backoff. libgphoto2's
                // 5 ms tight loop puts ~2000 0x9153 calls/10s through
                // the command channel which crashes the 6D's Wi-Fi
                // firmware (broken pipe observed in adb). Start at
                // 20 ms, escalate to 100 ms after the first second.
                _liveViewState.value = LiveViewState.Streaming(latestFrame = null)
                var busyStreak = 0
                var throwStreak = 0
                val warmupStart = System.currentTimeMillis()
                while (isActive) {
                    val outcome = runCatching {
                        activeClient.fetchViewFinderFrame()
                    }
                    if (outcome.isFailure) {
                        throwStreak++
                        if (throwStreak >= 3) {
                            _liveViewState.value = LiveViewState.Error(
                                "Live view stalled — reconnecting…"
                            )
                            break
                        }
                        kotlinx.coroutines.delay(200)
                        continue
                    }
                    throwStreak = 0
                    val frame = outcome.getOrNull()
                    if (frame == null || frame.size <= 0) {
                        busyStreak++
                        // ~30 s warmup ceiling. After that, give up
                        // and let the user tap Retry.
                        if (busyStreak >= 300) {
                            Log.w(TAG, "startLiveView: gave up after $busyStreak busy responses")
                            _liveViewState.value = LiveViewState.Error(
                                "Camera not delivering frames — try Retry"
                            )
                            break
                        }
                        // Escalating backoff to avoid overwhelming
                        // the 6D's firmware.
                        val elapsed = System.currentTimeMillis() - warmupStart
                        val delayMs = when {
                            elapsed < 1_000 -> 20L
                            elapsed < 5_000 -> 50L
                            else -> 100L
                        }
                        kotlinx.coroutines.delay(delayMs)
                        continue
                    }
                    busyStreak = 0
                    val bytes = frame.readByteArray()
                    val jpeg = extractJpegFromViewFinderFrame(bytes) ?: continue
                    _liveViewState.value = LiveViewState.Streaming(latestFrame = jpeg)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "startLiveView: uncaught — surfacing as Error", t)
                _liveViewState.value = LiveViewState.Error(
                    "Live view crashed: ${t.message}"
                )
            }
            // No finally-block teardown here — the LV coroutine is
            // usually cancelled when the user stops LV, and suspend
            // calls inside a cancelled coroutine immediately throw
            // CancellationException, leaving the camera half-armed.
            // [stopLiveView] launches the teardown in a SEPARATE,
            // non-cancelled coroutine on sessionScope instead.
        }
    }

    /**
     * Tear down the live-view stream. The teardown opcodes
     * (`0x913E(1)`, `0x9152`, `D1B0=0`, `D1B3=0`) are launched in
     * their own [sessionScope] coroutine so they actually reach the
     * wire — running them inside [liveViewJob]'s `finally` block
     * would fail because that job is being cancelled. Idempotent.
     */
    fun stopLiveView() {
        val toCancel = liveViewJob
        liveViewJob = null
        _liveViewState.value = LiveViewState.Idle
        // Cancel the polling job first, then send teardown opcodes
        // from a fresh coroutine that isn't itself cancelled.
        toCancel?.cancel()
        val activeClient = client ?: return
        sessionScope.launch {
            // Wait for the polling job to actually stop holding the
            // command-channel mutex; without this the teardown
            // writes race against an in-flight 0x9153 read and
            // either side can get a framing error.
            runCatching { toCancel?.join() }
            runCatching { activeClient.stopViewFinder() }
        }
    }

    /**
     * Strip Canon's viewfinder envelope and return the embedded JPEG.
     * The envelope starts with the magic `FF FF FF FF` then 60-ish
     * bytes of AF / WB / metadata. The JPEG begins at the first
     * `FF D8 FF` Start-Of-Image marker we can find.
     *
     * Returns null if no SOI is found — those frames are dropped.
     */
    private fun extractJpegFromViewFinderFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < 64) return null
        // Search for the JPEG SOI marker. Stop after 4 KB — the
        // envelope is always smaller.
        val searchEnd = minOf(bytes.size - 3, 4096)
        var i = 0
        while (i < searchEnd) {
            if (bytes[i] == 0xFF.toByte() &&
                bytes[i + 1] == 0xD8.toByte() &&
                bytes[i + 2] == 0xFF.toByte()
            ) {
                return bytes.copyOfRange(i, bytes.size)
            }
            i++
        }
        return null
    }

    /**
     * Trigger a remote capture. Sequence (libgphoto2 5Dm2-verified):
     *   1. 0x9128(1, 0) — half-press (AF)
     *   2. busy-wait ~400 ms for focus settle (libgphoto2 polls
     *      OLCInfo here; on the 6D the safety net is to just sleep
     *      and full-press anyway — the camera decides whether to
     *      actually release)
     *   3. 0x9128(2, 0) — full-press (release)
     *   4. 0x9129(2) — end release
     *   5. 0x9129(1) — end AF
     *
     * The image-ready event lands in the existing EOS event poller
     * as either 0xC181 (card destination) or 0xC186 (host RAM
     * destination) and triggers the auto-download pipeline if
     * configured.
     */
    fun triggerShutter() {
        val activeClient = client
        if (activeClient == null) {
            Log.w(TAG, "triggerShutter: ignored — client is null " +
                "(connectionState=${_connectionState.value})")
            return
        }
        if (_captureInProgress.value) {
            Log.w(TAG, "triggerShutter: ignored — capture already in progress")
            return
        }
        sessionScope.launch {
            _captureInProgress.value = true
            try {
                if (!activeClient.halfPressShutter()) {
                    Log.w(TAG, "triggerShutter: half-press refused")
                    return@launch
                }
                // libgphoto2-style focus settle delay. The 6D often
                // signals lock via OLCInfo events, but the safety
                // net is to just wait ~400 ms and full-press anyway.
                kotlinx.coroutines.delay(400)
                if (!activeClient.fullPressShutter()) {
                    Log.w(TAG, "triggerShutter: full-press refused")
                    return@launch
                }
                Log.i(TAG, "triggerShutter: SHOT TAKEN")
            } catch (t: Throwable) {
                Log.w(TAG, "triggerShutter: threw", t)
            } finally {
                // Always release both halves — leaving the camera
                // half-pressed prevents the next capture.
                runCatching { activeClient.releaseFullPress() }
                runCatching { activeClient.releaseHalfPress() }
                _captureInProgress.value = false
            }
        }
    }

    /**
     * AF-On press: half-press shutter WITH AF (`0x9128(1, 1)`). The
     * camera autofocuses and holds the lock as long as we keep the
     * half-press latched. Matches EOS Utility's AF-On button: hold to
     * focus, then a SEPARATE shutter-fire path takes the shot without
     * disturbing the lock.
     *
     * Tracks [_afEngaged] so the UI can show a green-filled AF-On
     * button while held.
     */
    fun doAf() = launchOnClient {
        _afEngaged.value = it.halfPressShutterWithAf()
    }

    /**
     * AF-On release: release the half-press latch (`0x9129(1)`) so the
     * camera drops focus-lock and returns to idle.
     */
    fun cancelAf() = launchOnClient {
        it.releaseHalfPress()
        _afEngaged.value = false
    }

    private val _afEngaged = MutableStateFlow(false)
    /** True while AF-On is actively held — used to colour the AF-On button green. */
    val afEngaged: StateFlow<Boolean> = _afEngaged.asStateFlow()

    /**
     * Manual-focus nudge for the 6-button MF panel.
     */
    fun driveLens(step: CanonWifiClient.LensFocusStep) =
        launchOnClient { it.driveLens(step.wireValue) }

    /**
     * Reposition the FlexiZone-AF rectangle in Live View. [normalisedX]
     * and [normalisedY] are 0..1 fractions of the LV image. Path per
     * Magic Lantern's `move_lv_afframe`:
     *
     *   1. Scale the tap into the camera's NATIVE SENSOR coord space
     *      (5472×3648 for the 6D) — that's `aff[]`'s coordinate system,
     *      NOT the LV preview's 1024×680 pixel grid.
     *   2. Write `PROP_LV_AFFRAME (0x80050007)` via opcode `0x915A`
     *      with the new box position. The camera moves its on-LCD
     *      FlexiZone rectangle within ~1 frame.
     *   3. Trigger `DoAf (0x9154)` so the camera AFs at the new spot.
     *
     * No-op when AfMethod is not FlexiZoneAF — switch via the AF-M
     * chip first or the camera will silently ignore the write.
     */
    fun setLiveAfPoint(normalisedX: Float, normalisedY: Float) {
        val activeClient = client ?: return
        sessionScope.launch {
            // Centre the AF box on the tap by offsetting back by half
            // the default box size. The wire layer applies the final
            // MIN_COORD clamp; we just compute the centre-as-top-left
            // shift here.
            val box = PtpIpConstants.LV_AFFRAME_DEFAULT_BOX_PX
            val centreX = (normalisedX.coerceIn(0f, 1f) *
                PtpIpConstants.SENSOR_6D_WIDTH).toInt()
            val centreY = (normalisedY.coerceIn(0f, 1f) *
                PtpIpConstants.SENSOR_6D_HEIGHT).toInt()
            val topLeftX = centreX - box / 2
            val topLeftY = centreY - box / 2
            val ok = runCatching {
                activeClient.setLvAfFrameProp(
                    boxX = topLeftX,
                    boxY = topLeftY,
                    boxWidth = box,
                    boxHeight = box,
                )
            }.getOrElse {
                Log.w(TAG, "setLiveAfPoint: setLvAfFrameProp threw", it)
                false
            }
            if (!ok) {
                Log.w(TAG, "setLiveAfPoint: write failed — check AF method " +
                    "is FlexiZone")
                return@launch
            }
            // Follow with DoAf so the camera focuses at the new spot.
            runCatching { activeClient.doAf() }
        }
    }

    /**
     * Configure post-capture image routing. Per
     * [PtpIpConstants.DPC_CANON_EOS_CAPTURE_DESTINATION] — values
     * 1 (card), 4 (host RAM), 3 (both).
     */
    fun setCaptureDestination(value: Int) =
        launchOnClient { it.setCaptureDestination(value) }

    /**
     * Download the user's chosen variant of a paired photo. When the
     * shot is RAW+JPG, the user typically wants the RAW for editing
     * and either format for download — caller picks the handle by
     * passing [CameraPhoto.editHandle] (RAW-preferred) or
     * [CameraPhoto.jpegHandle] (JPG-preferred). [filenameExt] should
     * be "CR2" or "JPG" to match the on-disk format.
     */
    fun downloadCameraPhoto(
        photo: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraPhoto,
        handle: Int,
        filenameExt: String,
        /**
         * Where to write the file. Caller normally passes the app's
         * Working Directory URI (the same `saveFolderUri` the rest of
         * the app uses). Falls back to the per-session armed folder
         * if null.
         */
        destinationFolder: android.net.Uri?,
        onComplete: ((android.net.Uri?) -> Unit)? = null,
    ) {
        val activeClient = client ?: return
        // Unified session-subfolder routing: every per-photo write
        // (Browse → Download, Browse → Edit) lands in the same
        // {CameraName}-{date} subfolder used by Batch and Live
        // Shooting. Single mental model for the user: one shoot day
        // = one folder.
        val target = destinationFolder
            ?.let { uri ->
                val sub = ensureSessionSubfolder(uri) ?: uri
                runCatching {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, sub)
                }.getOrNull()
                    ?.takeIf { it.isDirectory && it.canWrite() }
                    ?.let { SafCaptureTarget.forDirectory(context, it.uri) }
            }
            ?: makeTargetForArmedFolder()
            ?: run {
                Log.w(TAG, "downloadCameraPhoto: no destination folder")
                onComplete?.invoke(null)
                return
            }
        val filename = "${photo.displayName}.$filenameExt"
        val mimeType = guessMimeTypeFromFilename(filename)
        sessionScope.launch {
            _cameraBrowserBusy.value = true
            val allocation = runCatching {
                target.open(requestedFilename = filename, mimeType = mimeType)
            }.getOrNull()
            if (allocation == null) {
                Log.w(TAG, "downloadCameraPhoto: could not allocate sink for '$filename'")
                _cameraBrowserBusy.value = false
                onComplete?.invoke(null)
                return@launch
            }
            try {
                val result = activeClient.streamObjectViaPartial(
                    handle = handle,
                    destination = allocation.sink,
                )
                if (result is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult.Success) {
                    val uri = allocation.finalize()
                    Log.i(TAG, "downloadCameraPhoto: SUCCESS '$filename'")
                    onComplete?.invoke(uri)
                } else {
                    Log.w(TAG, "downloadCameraPhoto: $result for '$filename'")
                    allocation.discard()
                    onComplete?.invoke(null)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "downloadCameraPhoto: threw", t)
                allocation.runCatching { discard() }
                onComplete?.invoke(null)
            } finally {
                _cameraBrowserBusy.value = false
            }
        }
    }

    fun downloadCameraObject(
        obj: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectInfo,
        onComplete: ((android.net.Uri?) -> Unit)? = null,
    ) {
        val activeClient = client ?: return
        val target = makeTargetForArmedFolder() ?: return
        sessionScope.launch {
            _cameraBrowserBusy.value = true
            val mimeType = guessMimeTypeFromFilename(obj.filename)
            val allocation = runCatching {
                target.open(requestedFilename = obj.filename, mimeType = mimeType)
            }.getOrNull()
            if (allocation == null) {
                Log.w(TAG, "downloadCameraObject: could not allocate sink for '${obj.filename}'")
                _cameraBrowserBusy.value = false
                onComplete?.invoke(null)
                return@launch
            }
            try {
                val result = activeClient.streamObjectViaPartial(
                    handle = obj.handle,
                    destination = allocation.sink,
                    advertisedTotalBytes = obj.sizeBytes,
                )
                if (result is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult.Success) {
                    val uri = allocation.finalize()
                    Log.i(TAG, "downloadCameraObject: SUCCESS '${obj.filename}' (${obj.sizeBytes} B)")
                    onComplete?.invoke(uri)
                } else {
                    Log.w(TAG, "downloadCameraObject: $result for '${obj.filename}'")
                    allocation.discard()
                    onComplete?.invoke(null)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "downloadCameraObject: threw", t)
                allocation.runCatching { discard() }
                onComplete?.invoke(null)
            } finally {
                _cameraBrowserBusy.value = false
            }
        }
    }

    /**
     * SSDP-less camera discovery fallback. When the 6D's UPnP service
     * has gone silent (it's a one-shot in the EOS Utility wizard, dies
     * after the first PTP session opens), we can still reach the camera
     * on its known PTP/IP port (15740) — but we need its IP. Scan the
     * phone-hotspot subnet for any host with an open 15740 and assume
     * that's the 6D.
     *
     * Subnet derivation: read the phone's active interfaces, find the
     * AP one (typical Android tether IP `192.168.43.x`/`192.168.49.x`/
     * `192.168.238.x`), scan `.2`..`.254` skipping our own IP. First
     * host that ACKs a TCP connect on 15740 wins. ~250 ms per attempt
     * with a tight connect timeout; 254 hosts = ~60 s upper bound but
     * the camera is usually one of the first 5 addresses.
     *
     * Returns the camera IP or null if none answered.
     */
    private suspend fun scanForPtpIpOnHotspotSubnet(): String? =
        kotlinx.coroutines.coroutineScope {
            Log.i(TAG, "scanForPtpIpOnHotspotSubnet: START")
            val phoneAddrs = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter {
                        val ip = it.hostAddress.orEmpty()
                        ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")
                    }
            }.getOrNull().orEmpty()
            Log.i(TAG, "scanForPtpIpOnHotspotSubnet: phone addrs=${phoneAddrs.map { it.hostAddress }}")
            // Parallelize probes — Socket.connect with a 250 ms timeout
            // blocks one IO thread per host, so a serial scan walks
            // 253 hosts × 250 ms = up to 63 s worst case. With 16-way
            // fan-out the worst case drops to ~4 s. The Dispatchers.IO
            // pool defaults to 64 threads so 16 in-flight probes is
            // safely under quota.
            val winner = kotlinx.coroutines.CompletableDeferred<String?>()
            val probeJobs = mutableListOf<kotlinx.coroutines.Job>()
            val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL_PROBE_FANOUT)
            for (phoneAddr in phoneAddrs) {
                val phoneIp = phoneAddr.hostAddress ?: continue
                val prefix = phoneIp.substringBeforeLast('.')
                Log.i(TAG, "scanForPtpIpOnHotspotSubnet: trying prefix=$prefix.* (host=$phoneIp)")
                val phoneLast = phoneIp.substringAfterLast('.').toIntOrNull() ?: continue
                // Try low addresses first — Android hands out the .2 / .3 /
                // .4 to early DHCP clients in tether mode, and the camera
                // is usually the only client. With concurrent fan-out the
                // ordering matters less, but we still keep it as a hint
                // (the semaphore admits .2 / .3 / .4 / ... first).
                val candidates = (2..254).filter { it != phoneLast }
                for (last in candidates) {
                    if (winner.isCompleted) break
                    probeJobs += launch(kotlinx.coroutines.Dispatchers.IO) {
                        gate.acquire()
                        try {
                            if (winner.isCompleted || !isActive) return@launch
                            val ip = "$prefix.$last"
                            val ok = runCatching {
                                java.net.Socket().use { sock ->
                                    sock.connect(java.net.InetSocketAddress(ip, 15740), 250)
                                    sock.isConnected
                                }
                            }.getOrElse { false }
                            if (ok && winner.complete(ip)) {
                                Log.i(TAG, "scanForPtpIpOnHotspotSubnet: found PTP/IP at $ip")
                            }
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
            // Supervisor: if every probe finishes without a hit, complete
            // the deferred with null so the caller can fall through.
            launch {
                probeJobs.forEach { it.join() }
                if (!winner.isCompleted) winner.complete(null)
            }
            val result = winner.await()
            // Cancel any still-running probes so the IO threads free up
            // for InitCommand. The 250 ms socket timeout caps the
            // already-blocked syscalls; we don't need to wait on them.
            probeJobs.forEach { it.cancel() }
            result
        }


    /**
     * Poll the camera's PTP/IP port (15740) until it accepts a TCP
     * connect — that signals the 6D's pairing state machine has
     * transitioned out of "Searching for EOS Utility" and is ready
     * for InitCommand. While polling, re-issue the
     * CameraDevDesc.xml GET periodically with the EOS-Utility
     * User-Agent header — verified against PC_EOSUTILITY*.pcapng,
     * which all show EU re-fetching the XML at every M-SEARCH
     * cycle. The 6D firmware needs to see a sustained
     * EOS-Utility-shaped HTTP presence before it'll open 15740.
     *
     * Returns true if the listener opened within [timeoutMs], false
     * if the user never confirmed pairing on the body.
     */
    private suspend fun waitForPtpListener(
        cameraIp: String,
        network: android.net.Network?,
        ssdpLocation: String?,
        timeoutMs: Long,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var attempt = 0
        var lastNudgeNs = 0L
        val nudgeIntervalNs = 3_000L * 1_000_000L // re-GET XML every 3 s
        while (System.nanoTime() < deadline) {
            if (!isActive) return@withContext false
            attempt++
            val open = runCatching {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(cameraIp, 15740), 1_000)
                    sock.isConnected
                }
            }.getOrElse { false }
            if (open) {
                Log.i(TAG, "waitForPtpListener: port 15740 OPEN at $cameraIp (attempt=$attempt)")
                return@withContext true
            }
            // Nudge the camera with another XML GET every 3 s. The
            // first GET happened during discovery; this keeps the
            // EOS-Utility User-Agent visible to firmware so the
            // pairing-prompt state machine doesn't time out and
            // revert to idle.
            val now = System.nanoTime()
            if (ssdpLocation != null && (now - lastNudgeNs) >= nudgeIntervalNs) {
                lastNudgeNs = now
                runCatching {
                    val url = java.net.URL(ssdpLocation)
                    val conn = (network?.openConnection(url) ?: url.openConnection())
                        as java.net.HttpURLConnection
                    conn.connectTimeout = 2_000
                    conn.readTimeout = 2_000
                    conn.useCaches = false
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.setRequestProperty("Connection", "Close")
                    conn.setRequestProperty("Pragma", "no-cache")
                    conn.setRequestProperty("Accept", "text/xml, application/xml")
                    conn.setRequestProperty("User-Agent", "Microsoft-Windows/10.0 UPnP/1.0")
                    conn.inputStream.use { it.readBytes() }
                    Log.d(TAG, "waitForPtpListener: nudge GET ok (attempt=$attempt)")
                }.onFailure {
                    Log.d(TAG, "waitForPtpListener: nudge GET failed: ${it.message}")
                }
            }
            kotlinx.coroutines.delay(500)
        }
        Log.w(TAG, "waitForPtpListener: gave up after ${timeoutMs}ms ($attempt attempts)")
        false
    }

    /**
     * Recognise "the TCP socket is gone" exceptions emanating from
     * `CanonWifiClient.runOperation` so callers can bail out of in-flight
     * batches instead of hammering a dead socket. The heartbeat will
     * declare the session dead within ~9 s and trigger auto-reconnect;
     * we just want to stop burning round-trips in the meantime.
     */
    private fun isSocketDeadException(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return t is java.io.EOFException ||
            (t is java.net.SocketException && msg.contains("Broken pipe", ignoreCase = true)) ||
            (t is java.net.SocketException && msg.contains("ECONNRESET", ignoreCase = true)) ||
            (t is java.io.IOException && msg.contains("Broken pipe", ignoreCase = true))
    }

    private fun guessMimeTypeFromFilename(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "cr2" -> "image/x-canon-cr2"
            "cr3" -> "image/x-canon-cr3"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    /**
     * Cold flow of remembered camera profiles, MRU-first, capped at
     * [CanonSyncPreferences.MAX_CAMERA_PROFILES]. UI surfaces these as
     * Quick-Connect tiles.
     */
    val cameraProfiles: kotlinx.coroutines.flow.Flow<List<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile>> =
        preferences.cameraProfiles

    /** Wipe a single profile (invoked from the delete-with-confirm dialog). */
    suspend fun forgetCameraProfile(cameraMac: String) {
        preferences.forgetCameraProfile(cameraMac)
    }

    /**
     * Rename a saved camera profile's UI alias. Pass null to clear and
     * fall back to the autoresolved displayName cascade.
     */
    suspend fun renameCameraProfile(cameraMac: String, alias: String?) {
        preferences.setCameraProfileAlias(cameraMac, alias)
    }

    fun setFormatMode(mode: FormatMode) {
        _formatMode.value = mode
        sessionScope.launch { preferences.setFormatMode(mode) }
    }

    // ── Connect helpers ──────────────────────────────────────────────
    //
    // The full connect flow is 6 stages — network acquisition, fast-path
    // profile try, parallel discovery race, retry-with-nudges, profile
    // persistence, and session-infrastructure stand-up. Each stage is a
    // private helper below so [connect] reads as a top-level
    // orchestration of named steps. None of these mutate `client` /
    // `_connectionState` / `lastConnectArgs` — those writes stay in
    // [connect] itself so the data-flow remains visible at one site.

    /**
     * Result of the discovery race + IP/GUID resolution. When [success]
     * is true, [cameraIp] and [initGuid] are populated; otherwise the
     * caller surfaces [errorMessage] as the failure reason.
     */
    private data class DiscoveryOutcome(
        val success: Boolean,
        val cameraIp: String = "",
        val initGuid: java.util.UUID = java.util.UUID(0L, 0L),
        val ssdpAd: WftUpnpDiscoverer.CanonCameraAdvertisement? = null,
        val errorMessage: String = "",
    )

    /** Result of the fast-path profile sweep. */
    private data class FastPathResult(
        val client: CanonWifiClient?,
        val profile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile?,
        val result: ConnectResult.Success?,
    )

    /**
     * Acquire the camera Wi-Fi network handle. Returns the
     * [NetworkBindResult.Acquired] on success, or a
     * [ConnectResult.Failure.SocketUnreachable] with a user-readable
     * message on any other [NetworkBindResult] variant.
     */
    private suspend fun acquireCameraNetwork(
        binder: NetworkBinder,
    ): Pair<NetworkBindResult.Acquired?, String?> {
        Log.d(TAG, "repository.connect: acquiring camera Wi-Fi")
        val networkResult = binder.acquireCameraWifi()
        if (networkResult is NetworkBindResult.Acquired) {
            Log.i(TAG, "repository.connect: network acquired = ${networkResult.network}")
            return Pair(networkResult, null)
        }
        val message = when (networkResult) {
            is NetworkBindResult.Failure.Unsupported -> "Requires Android 10 or later"
            is NetworkBindResult.Failure.PermissionMissing -> "Nearby Wi-Fi permission required"
            is NetworkBindResult.Failure.UserDeclined -> "User did not pick a camera Wi-Fi"
            is NetworkBindResult.Failure.Timeout -> "Timed out waiting for camera Wi-Fi"
            is NetworkBindResult.Failure.Error -> networkResult.message
            is NetworkBindResult.Failure.WrongNetwork ->
                "Join the camera's Wi-Fi in Android Settings first, then tap Connect."
            is NetworkBindResult.Acquired -> error("unreachable")
        }
        Log.w(TAG, "repository.connect: NetworkBinder returned $networkResult -> '$message'")
        return Pair(null, message)
    }

    /**
     * Try every cached camera profile (preferred first, then MRU
     * order) with a single InitCommand each. First hit wins; on miss
     * the caller falls through to discovery.
     *
     * We do NOT loop with ECONNREFUSED nudges here — that's
     * discovery's job. If the cached IP refuses, the camera moved or
     * rebooted; fall through to discovery so we re-find it.
     */
    private suspend fun tryFastPathProfiles(
        network: android.net.Network?,
        hostName: String,
        preferredProfile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile?,
    ): FastPathResult {
        val candidates = buildList {
            if (preferredProfile != null) add(preferredProfile)
            val others = runCatching { preferences.cameraProfiles.first() }
                .getOrNull().orEmpty()
            addAll(others.filter { it.cameraMac != preferredProfile?.cameraMac })
        }
        // Prefixes of the phone's CURRENT Wi-Fi subnet(s). A cached profile
        // whose lastIp isn't on one of them is unreachable from where we are
        // now (e.g. a `10.202.x.x` carrier-CGNAT address saved on a previous
        // network) — a fast-path attempt to it just blocks ~5 s on a socket
        // timeout before we fall through to discovery. Skip those; discovery
        // re-finds the camera on the current subnet.
        val phonePrefixes = phoneSubnetPrefixes()
        for (profile in candidates) {
            val ipPrefix = profile.lastIp.substringBeforeLast('.')
            if (phonePrefixes.isNotEmpty() && ipPrefix !in phonePrefixes) {
                Log.i(TAG, "repository.connect: fast-path SKIP ${profile.cameraMac} @ " +
                    "${profile.lastIp} — not on phone subnet(s) $phonePrefixes " +
                    "(avoids ~5s dead-IP stall)")
                continue
            }
            Log.i(TAG, "repository.connect: fast-path try ${profile.cameraMac} @ ${profile.lastIp}")
            val candidate = CanonWifiClient()
            val outcome = candidate.connect(
                network = network,
                cameraIp = profile.lastIp,
                hostGuid = profile.hostGuid,
                hostName = hostName,
            )
            if (outcome is ConnectResult.Success) {
                Log.i(TAG, "repository.connect: fast-path HIT ${profile.cameraMac}")
                return FastPathResult(candidate, profile, outcome)
            }
            Log.i(TAG, "repository.connect: fast-path miss ${profile.cameraMac}: $outcome")
        }
        return FastPathResult(null, null, null)
    }

    /**
     * The `a.b.c` /24 prefixes of every private IPv4 address currently bound
     * to a phone interface — i.e. the subnet(s) the phone can actually reach
     * directly. Used to reject stale cached camera IPs from other networks
     * before wasting a socket-connect timeout on them. Empty if none can be
     * read (in which case callers must NOT skip — fall back to trying).
     */
    private fun phoneSubnetPrefixes(): Set<String> =
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter {
                    it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.")
                }
                .map { it.substringBeforeLast('.') }
                .toSet()
        }.getOrNull().orEmpty()

    /**
     * Race SSDP discovery against subnet scan. First to find the camera
     * wins; loser is cancelled (best-effort — blocking sockets may take
     * up to their full timeout to actually return, but the user no
     * longer waits for it).
     *
     * NOTE: do NOT wrap this race in `coroutineScope { ... }`. The
     * structured-concurrency contract means coroutineScope waits for
     * ALL child jobs to complete before returning — including ones
     * we've .cancel()'d. SSDP's blocking MulticastSocket.receive
     * ignores Kotlin cancellation; we'd see 60-120 s tails. Use the
     * long-lived [sessionScope] instead.
     */
    private suspend fun raceDiscovery(
        network: android.net.Network?,
    ): DiscoveryOutcome {
        Log.i(TAG, "repository.connect: racing SSDP discovery + subnet scan")
        val winner = kotlinx.coroutines.CompletableDeferred<
            Pair<WftUpnpDiscoverer.CanonCameraAdvertisement?, String?>
        >()
        // ── ARP pre-pass: short-circuit on a known Canon OUI ─────────
        //   Read the kernel ARP table for any host whose MAC starts
        //   with a registered Canon OUI. If one's present we'll TCP-
        //   probe it FIRST (with the same 250 ms timeout) — typically
        //   takes ~100 ms when the camera is on the AP, vs ~60 s for
        //   a full /24 sweep on a busy subnet. We don't gate SSDP on
        //   this result; both still race in case the camera self-
        //   advertises faster (or the ARP entry hasn't populated yet).
        val arpHits = com.RAZStudio.StudioRoom.feature.canon_sync.net
            .CanonOui.arpScan(TAG)
        if (arpHits.isEmpty()) {
            Log.i(TAG, "discovery: ARP table has no known Canon OUI " +
                "— camera may not yet be on the network")
        } else {
            Log.i(TAG, "discovery: ARP found ${arpHits.size} Canon device(s): " +
                arpHits.joinToString { "${it.ip}=${it.matchedMac}" })
        }
        val arpJob = sessionScope.launch {
            for (hit in arpHits) {
                if (winner.isCompleted) return@launch
                val ok = runCatching {
                    java.net.Socket().use { sock ->
                        sock.connect(java.net.InetSocketAddress(hit.ip, 15740), 500)
                        true
                    }
                }.getOrDefault(false)
                if (ok && !winner.isCompleted) {
                    Log.i(TAG, "discovery: ARP-hit ${hit.ip} accepted 15740 — using it")
                    winner.complete(Pair(null, hit.ip))
                    return@launch
                }
            }
        }
        val ssdpJob = sessionScope.launch {
            val ad = WftUpnpDiscoverer(
                context = context,
                network = network,
            ).discoverFirst()
            if (ad != null) {
                winner.complete(Pair(ad, null))
            }
        }
        val scanJob = sessionScope.launch {
            val ip = scanForPtpIpOnHotspotSubnet()
            if (ip != null) {
                // Brief grace window so SSDP can still beat us with a
                // fresh camera-issued targetId.
                withTimeoutOrNull(750L) { ssdpJob.join() }
                if (!winner.isCompleted) {
                    winner.complete(Pair(null, ip))
                }
            }
        }
        // If both probes finish without a hit, complete with a
        // double-null so the caller can surface the failure message.
        val supervisor = sessionScope.launch {
            arpJob.join()
            ssdpJob.join()
            scanJob.join()
            if (!winner.isCompleted) {
                winner.complete(Pair(null, null))
            }
        }
        val out = winner.await()
        arpJob.cancel()
        ssdpJob.cancel()
        scanJob.cancel()
        supervisor.cancel()
        val (ssdpAd, scannedIp) = out
        return when {
            ssdpAd != null -> {
                Log.i(TAG, "repository.connect: SSDP discovered ${ssdpAd.modelName} " +
                    "(friendlyName='${ssdpAd.friendlyName}') @ ${ssdpAd.ip} " +
                    "targetId=${ssdpAd.targetId}")
                // The 6D advertises `00000000-0000-0000-0001-FFFFFFFFFFFF`
                // as its X_targetId BEFORE any host has paired AND as a
                // default placeholder even between sessions. Echoing
                // that sentinel back as our InitCommand GUID puts the
                // camera in a state where it thinks "no host is here
                // yet" and shows the on-body "Searching for EOS
                // Utility" prompt forever. Substitute our persisted
                // Canon-namespaced host GUID so the camera fires its
                // on-body "EOS Utility found — Pair?" confirmation and
                // arms port 15740.
                // An unpaired 6D advertises one of TWO placeholder targetIds:
                //   • 00000000-0000-0000-0001-FFFFFFFFFFFF  (X_targetId present,
                //     placeholder), or
                //   • 00000000-0000-0000-0000-000000000000  (CameraDevDesc.xml
                //     has NO X_targetId at all — the first-time-pairing case,
                //     WftUpnpDiscoverer returns the all-zeros UUID).
                // Echoing EITHER back as our InitCommand host GUID leaves the
                // camera thinking "no host here yet" — it sits on "Searching for
                // EOS Utility" and NEVER fires the on-body "Pair?" prompt. In
                // both cases send our OWN persisted host GUID (what EOS Utility
                // does — it always sends its own stable client GUID) so the
                // camera fires the pairing confirmation and arms port 15740.
                val sentinel = java.util.UUID.fromString(
                    "00000000-0000-0000-0001-ffffffffffff"
                )
                val zeroGuid = java.util.UUID(0L, 0L) // 00000000-…-000000000000
                val guid = if (ssdpAd.targetId == sentinel || ssdpAd.targetId == zeroGuid) {
                    val host = preferences.hostGuid()
                    Log.i(TAG, "repository.connect: targetId is unpaired-sentinel " +
                        "(${ssdpAd.targetId}); substituting host GUID $host")
                    host
                } else {
                    ssdpAd.targetId
                }
                DiscoveryOutcome(
                    success = true,
                    cameraIp = ssdpAd.ip,
                    initGuid = guid,
                    ssdpAd = ssdpAd,
                )
            }
            scannedIp != null -> {
                Log.i(TAG, "repository.connect: subnet scan found PTP/IP at $scannedIp (no SSDP)")
                DiscoveryOutcome(
                    success = true,
                    cameraIp = scannedIp,
                    // Without SSDP we have no camera-issued targetId.
                    // Use the host-side persisted GUID — the 6D will
                    // accept it once pairing has been completed
                    // previously.
                    initGuid = preferences.hostGuid(),
                )
            }
            else -> {
                Log.w(TAG, "repository.connect: neither SSDP nor subnet scan found a camera")
                // Tailor the message based on whether ANY Canon device
                // was seen at L2. If ARP had no Canon OUI, the camera
                // probably isn't powered on / not joined to Wi-Fi —
                // tell the user to fix that. If ARP DID see a Canon
                // device but PTP/IP didn't answer, the camera is on
                // the AP but the Wi-Fi function is in the wrong mode
                // (e.g. Image Transfer instead of EOS Utility).
                val msg = if (arpHits.isEmpty()) {
                    "No Canon camera detected on this Wi-Fi. Turn on the camera's " +
                        "Wi-Fi function and connect your phone to its hotspot (or " +
                        "join the same access point the camera is on)."
                } else {
                    "Canon device seen on Wi-Fi (${arpHits.first().matchedMac}) but " +
                        "it didn't answer PTP/IP. Power-cycle the 6D and re-enter " +
                        "'Remote control (EOS Utility)' mode, then tap Connect."
                }
                DiscoveryOutcome(success = false, errorMessage = msg)
            }
        }
    }

    /**
     * Connect with retry. On ECONNREFUSED nudge the camera with a
     * `CameraDevDesc.xml` GET (User-Agent: Microsoft-Windows EOS
     * Utility) and retry. Real EOS Utility pcaps show this exact
     * pattern. Deadline-capped at 45 s.
     *
     * Returns the first successful [ConnectResult.Success] and the
     * winning [CanonWifiClient], OR null on any definitive failure.
     */
    private suspend fun connectWithRetries(
        network: android.net.Network?,
        cameraIp: String,
        initGuid: java.util.UUID,
        hostName: String,
        ssdpAd: WftUpnpDiscoverer.CanonCameraAdvertisement?,
    ): Pair<CanonWifiClient, ConnectResult>? {
        val ssdpLocation = ssdpAd?.let { "http://${it.ip}:49152/upnp/CameraDevDesc.xml" }
        val retryDeadline = System.nanoTime() + 45_000L * 1_000_000L
        var attempt = 0
        while (System.nanoTime() < retryDeadline) {
            attempt++
            val candidateClient = CanonWifiClient()
            Log.i(TAG, "repository.connect: InitCommand attempt #$attempt → $cameraIp")
            val outcome = candidateClient.connect(
                network = network,
                cameraIp = cameraIp,
                hostGuid = initGuid,
                hostName = hostName,
            )
            if (outcome is ConnectResult.Success) {
                return Pair(candidateClient, outcome)
            }
            // Failure path — decide whether to retry or surface.
            //
            // Two failure modes are recoverable by re-nudging the camera
            // with a CameraDevDesc.xml GET and retrying:
            //   1. ECONNREFUSED — camera's TCP listener is closed
            //      (pairing state machine not armed yet).
            //   2. Socket connect timeout ("failed to connect ... after
            //      Nms") — the listener got opened then closed by a
            //      previous probe (e.g. our own subnet scan burned its
            //      one-shot slot, or another host is racing us). Same
            //      remedy as ECONNREFUSED: nudge + retry.
            // Anything else (InitCommand rejected, post-handshake
            // ResponseCode failure, etc.) is a definitive failure.
            // EHOSTUNREACH = the kernel has no ARP route to the target.
            // That's not a "camera is booting" problem — the camera
            // isn't on the L2 network at all. Retrying for 45 s just
            // freezes the UI in Connecting… while the user thinks
            // "Canon Sync can't be opened". Bail immediately so the
            // user can hit Scan / reconnect Wi-Fi.
            val routeMissing = outcome is ConnectResult.Failure.SocketUnreachable &&
                outcome.cause.contains("EHOSTUNREACH", ignoreCase = true)
            if (routeMissing) {
                Log.w(TAG, "repository.connect: EHOSTUNREACH — camera not on network, aborting retries")
                return Pair(candidateClient, outcome)
            }
            val retriable = outcome is ConnectResult.Failure.SocketUnreachable && (
                outcome.cause.contains("ECONNREFUSED", ignoreCase = true) ||
                outcome.cause.contains("failed to connect", ignoreCase = true)
            )
            if (!retriable) {
                Log.w(TAG, "repository.connect: non-retriable failure $outcome — aborting retries")
                return Pair(candidateClient, outcome)
            }
            // Hard cap: 3 attempts max even if deadline allows more.
            // The 6D's pairing state machine recovers on the 2nd or
            // 3rd retry if it's going to recover at all; further
            // attempts just delay the inevitable failure message.
            if (attempt >= 3) {
                Log.w(TAG, "repository.connect: $attempt attempts hit cap — surfacing failure")
                return Pair(candidateClient, outcome)
            }
            Log.i(TAG, "repository.connect: $outcome — nudging camera and retrying in 2s")
            if (ssdpLocation != null) {
                runCatching {
                    val url = java.net.URL(ssdpLocation)
                    val conn = (network?.openConnection(url) ?: url.openConnection())
                        as java.net.HttpURLConnection
                    conn.connectTimeout = 2_000
                    conn.readTimeout = 2_000
                    conn.useCaches = false
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.setRequestProperty("Connection", "Close")
                    conn.setRequestProperty("Pragma", "no-cache")
                    conn.setRequestProperty("Accept", "text/xml, application/xml")
                    conn.setRequestProperty("User-Agent", "Microsoft-Windows/10.0 UPnP/1.0")
                    conn.inputStream.use { it.readBytes() }
                }
            }
            kotlinx.coroutines.delay(2_000L)
        }
        Log.w(TAG, "repository.connect: InitCommand never succeeded after $attempt attempts")
        return null
    }

    /**
     * Persist a Quick-Connect profile keyed by the camera's MAC so a
     * future reconnect can skip discovery entirely. Source for the
     * MAC, in priority order:
     *   1. Fresh SSDP advertisement.
     *   2. Preferred profile we entered with (fast-path winner).
     *   3. First profile whose lastIp matches the connected IP.
     *
     * If none of those identify a MAC, the save is skipped — a
     * profile with no MAC key would be unaddressable. Updates
     * [lastConnectArgs] with the resolved MAC so the auto-reconnect
     * supervisor can later look up this profile.
     *
     * Fast-path connects skip the CameraDevDesc.xml GET entirely, so
     * a user-renamed nickname on the camera body wouldn't update the
     * saved profile. The [ssdpAd == null] branch fires a background
     * fetch (fire-and-forget) so the next display picks it up.
     */
    private suspend fun persistProfileFromSuccess(
        connect: ConnectResult.Success,
        effectiveCameraIp: String,
        initGuid: java.util.UUID,
        ssdpAd: WftUpnpDiscoverer.CanonCameraAdvertisement?,
        fastWinProfile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile?,
        network: android.net.Network?,
    ) {
        val resolvedMac = ssdpAd?.cameraMac
            ?: fastWinProfile?.cameraMac
            ?: preferences.cameraProfiles.first()
                .firstOrNull { it.lastIp == effectiveCameraIp }?.cameraMac
        if (resolvedMac == null) {
            Log.d(TAG, "repository.connect: no MAC available, skipping profile save")
            return
        }
        val existing = preferences.cameraProfile(resolvedMac)
        val savedFriendly = ssdpAd?.friendlyName
            ?: existing?.friendlyName
            ?: connect.camera.model
        val savedModel = ssdpAd?.modelName
            ?: existing?.model
            ?: connect.camera.model
        val savedSerial = ssdpAd?.serialNumber?.takeIf { it.isNotBlank() }
            ?: existing?.serialNumber
            ?: ""
        preferences.saveCameraProfile(
            com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile(
                cameraMac = resolvedMac,
                lastIp = effectiveCameraIp,
                hostGuid = initGuid,
                model = savedModel,
                friendlyName = savedFriendly,
                serialNumber = savedSerial,
                alias = existing?.alias,
                lastSuccessEpochMs = System.currentTimeMillis(),
            )
        )
        Log.i(TAG, "repository.connect: saved profile mac=$resolvedMac ip=$effectiveCameraIp")
        lastConnectArgs = lastConnectArgs?.copy(cameraMac = resolvedMac)
        if (ssdpAd == null) {
            sessionScope.launch {
                runCatching {
                    val url = java.net.URL(
                        "http://$effectiveCameraIp:49152/upnp/CameraDevDesc.xml"
                    )
                    val conn = (network?.openConnection(url)
                        ?: url.openConnection()) as java.net.HttpURLConnection
                    conn.connectTimeout = 2_000
                    conn.readTimeout = 2_000
                    conn.useCaches = false
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Microsoft-Windows/10.0 UPnP/1.0")
                    conn.setRequestProperty("Accept", "text/xml, application/xml")
                    val xml = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    val friendly = Regex("<friendlyName>([^<]+)</friendlyName>")
                        .find(xml)?.groupValues?.getOrNull(1)?.trim()
                    val model = Regex("<modelName>([^<]+)</modelName>")
                        .find(xml)?.groupValues?.getOrNull(1)?.trim()
                    val serial = Regex("<serialNumber>([^<]+)</serialNumber>")
                        .find(xml)?.groupValues?.getOrNull(1)?.trim()
                    if (!friendly.isNullOrBlank() &&
                        (friendly != savedFriendly || model != savedModel ||
                            (!serial.isNullOrBlank() && serial != savedSerial))
                    ) {
                        Log.i(TAG, "background-refresh: updating profile friendly='$friendly'")
                        val current = preferences.cameraProfile(resolvedMac) ?: return@runCatching
                        preferences.saveCameraProfile(
                            current.copy(
                                model = model ?: current.model,
                                friendlyName = friendly,
                                serialNumber = serial ?: current.serialNumber,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Stand up the long-lived background workers tied to a session:
     *  - SSID-pin via [NetworkBinder.pinCameraWifiBySsid] — tells the
     *    OS not to drop this no-internet Wi-Fi.
     *  - [HeartbeatLoop] — 3s `KeepDeviceOn` ping to defeat the 6D's
     *    NIC sleep timer; flips state to Failed after 3 consecutive
     *    misses so the reconnect supervisor kicks in.
     *  - [EosPropertyController] — UI's data source for chip values.
     *
     * Mutates [ssidPinRelease], [heartbeatJob], [_propertyController],
     * [propertyControllerJob]. Idempotent only via [tearDownSession].
     */
    private fun startSessionInfrastructure(
        binder: NetworkBinder,
        newClient: CanonWifiClient,
    ) {
        ssidPinRelease = binder.pinCameraWifiBySsid()
        heartbeatJob = HeartbeatLoop(
            pingFn = { newClient.keepDeviceOn() },
            onSessionDeadDetected = {
                Log.w(TAG, "repository: heartbeat declared session dead — " +
                    "marking Failed to trigger auto-reconnect")
                if (_connectionState.value == ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Failed
                    _lastError.value = "Camera dropped Wi-Fi. Reconnecting…"
                    sessionScope.launch { tearDownSession() }
                }
            },
        ).start(sessionScope)
        val controller = EosPropertyController(newClient)
        _propertyController = controller
        propertyControllerJob = controller.start(sessionScope)
    }

    /**
     * Bring up the full session: request the camera Wi-Fi, open PTP/IP, start
     * the capture coordinator if a folder is already armed.
     *
     * Idempotent — calling [connect] while already connected is a no-op.
     */
    suspend fun connect(
        cameraIp: String,
        hostName: String,
        /**
         * Optional pre-selected profile (typically from the Quick-Connect
         * tile the user tapped). When set, we try its cached IP + host
         * GUID FIRST and only fall back to discovery if that fails.
         */
        preferredProfile: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CameraProfile? = null,
    ): ConnectResult = connectMutex.withLock {
        Log.i(TAG, "repository.connect: cameraIp=$cameraIp preferred=${preferredProfile?.cameraMac}")
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting
        ) {
            Log.w(TAG, "repository.connect: already in state ${_connectionState.value}, refusing")
            return@withLock ConnectResult.Failure.ProtocolError("Session already in progress")
        }
        // Capture args BEFORE we attempt — the auto-reconnect supervisor
        // re-uses these when the 6D drops mid-session. Cleared by the
        // public disconnect() entry point so a user-initiated teardown
        // doesn't trigger reconnect.
        lastConnectArgs = LastConnectArgs(
            cameraIp = cameraIp,
            hostName = hostName,
            // Preserve the previously-known MAC across reconnects so the
            // auto-reconnect supervisor can still hit the fast path.
            cameraMac = lastConnectArgs?.cameraMac ?: preferredProfile?.cameraMac,
        )
        _connectionState.value = ConnectionState.Connecting
        _lastError.value = null

        // ── Stage 1: acquire camera Wi-Fi ────────────────────────────
        val binder = NetworkBinder(context)
        val (networkResult, networkError) = acquireCameraNetwork(binder)
        if (networkResult == null) {
            _connectionState.value = ConnectionState.Failed
            _lastError.value = networkError ?: "Camera Wi-Fi unavailable"
            return@withLock ConnectResult.Failure.SocketUnreachable(
                networkError ?: "Camera Wi-Fi unavailable",
            )
        }
        networkRelease = networkResult.release

        // ── Stage 2: fast-path try cached profiles ───────────────────
        // Skips the 60s SSDP wait + 1-38s subnet scan when a remembered
        // camera matches. Preferred profile first (Quick-Connect tile),
        // then MRU walk. First success wins.
        val fastPath = tryFastPathProfiles(
            network = networkResult.network,
            hostName = hostName,
            preferredProfile = preferredProfile,
        )

        // ── Stage 3: discovery (skipped when fast path hit) ──────────
        val effectiveCameraIp: String
        val initGuid: java.util.UUID
        val ssdpAd: WftUpnpDiscoverer.CanonCameraAdvertisement?
        if (fastPath.result != null && fastPath.profile != null) {
            effectiveCameraIp = fastPath.profile.lastIp
            initGuid = fastPath.profile.hostGuid
            ssdpAd = null
        } else {
            val discovery = raceDiscovery(networkResult.network)
            if (!discovery.success) {
                tearDownSession()
                _connectionState.value = ConnectionState.Failed
                _lastError.value = discovery.errorMessage
                return@withLock ConnectResult.Failure.SocketUnreachable(discovery.errorMessage)
            }
            effectiveCameraIp = discovery.cameraIp
            initGuid = discovery.initGuid
            ssdpAd = discovery.ssdpAd
        }

        // ── Stage 4: connect with retries ────────────────────────────
        // Fast-path hit: we already have a winning client. Otherwise
        // retry InitCommand against the discovered IP, nudging the
        // camera with a CameraDevDesc.xml GET between attempts.
        val connect: ConnectResult
        if (fastPath.result != null && fastPath.client != null) {
            client = fastPath.client
            connect = fastPath.result
        } else {
            val attemptResult = connectWithRetries(
                network = networkResult.network,
                cameraIp = effectiveCameraIp,
                initGuid = initGuid,
                hostName = hostName,
                ssdpAd = ssdpAd,
            )
            if (attemptResult == null) {
                tearDownSession()
                _connectionState.value = ConnectionState.Failed
                val msg = "Camera shows 'Searching for EOS Utility'. " +
                    "Press SET on the camera body to confirm pairing, then tap Connect again."
                _lastError.value = msg
                return@withLock ConnectResult.Failure.SocketUnreachable(msg)
            }
            client = attemptResult.first
            connect = attemptResult.second
        }
        if (connect !is ConnectResult.Success) {
            Log.w(TAG, "repository.connect: client returned failure $connect, tearing down")
            tearDownSession()
            _connectionState.value = ConnectionState.Failed
            _lastError.value = describeConnectFailure(connect)
            return@withLock connect
        }
        val newClient = client ?: error("client field unset after successful connect")
        Log.i(TAG, "repository.connect: SUCCESS — session is live")
        currentCameraModel = connect.camera.model

        // ── Stage 5: persist profile + background-refresh nickname ───
        persistProfileFromSuccess(
            connect = connect,
            effectiveCameraIp = effectiveCameraIp,
            initGuid = initGuid,
            ssdpAd = ssdpAd,
            fastWinProfile = fastPath.profile,
            network = networkResult.network,
        )

        // ── Stage 6: stand up session infrastructure ─────────────────
        startSessionInfrastructure(binder, newClient)
        _connectionState.value = ConnectionState.Connected
        // Live-shooting coordinator stands up lazily via
        // ensureCaptureCoordinator() when the Remote Shoot screen
        // mounts. Eagerly calling rebuildCoordinatorIfArmed() here
        // used to create the per-session subfolder on every connect
        // even for users who only Download / Batch — that's now
        // deferred.
        return@withLock connect
    }

    fun disconnect() {
        // User-initiated teardown. Clear the reconnect-args so the
        // auto-reconnect supervisor doesn't keep trying to reconnect
        // behind the user's back. Per user requirement, ALL per-session
        // caches (grid thumbs + preview LRU + manifest) get wiped here
        // so the next pairing starts clean.
        lastConnectArgs = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        wipeSessionCaches()
        sessionScope.launch { tearDownSession() }
    }

    /**
     * Re-establish the PTP/IP session after a drop. Walks an exponential
     * backoff (2 s → 4 s → 8 s → 15 s → 30 s, capped) and stops when
     * either a connect succeeds or the user taps Disconnect (which
     * nulls [lastConnectArgs]).
     */
    private fun scheduleAutoReconnect() {
        // Hard guard against parallel reconnect loops. The previous bug:
        // _connectionState observer fired scheduleAutoReconnect each time
        // a connect() attempt failed back to Failed, spawning a fresh
        // coroutine that ran in parallel with the original — exponential
        // blowup of concurrent connect attempts (5+ visible in logcat).
        synchronized(this) {
            if (autoReconnectJob?.isActive == true) {
                Log.d(TAG, "auto-reconnect: already running, skipping schedule")
                return
            }
            _reconnecting.value = true
            autoReconnectJob = sessionScope.launch {
                try {
                    runAutoReconnectLoop()
                } finally {
                    // Whether we reconnected (state now Connected → FGS stays
                    // via the Connected branch) or gave up (terminal Failed →
                    // FGS releases), stop pinning the service on the reconnect
                    // flag.
                    _reconnecting.value = false
                    autoReconnectJob = null
                }
            }
        }
    }

    private suspend fun runAutoReconnectLoop() {
        // First retry is FAST (500 ms) — the typical mid-session drop is
        // a transient Broken-pipe where the camera AP is still reachable
        // and the cached profile lets us re-pair in ~3 s. Only escalate
        // to the longer waits when the fast path fails too.
        val backoffs = listOf(500L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        var attempt = 0
        while (lastConnectArgs != null && attempt < MAX_AUTO_RECONNECT_ATTEMPTS) {
            val delayMs = backoffs[attempt.coerceAtMost(backoffs.lastIndex)]
            Log.i(TAG, "auto-reconnect: attempt #${attempt + 1}/" +
                "$MAX_AUTO_RECONNECT_ATTEMPTS in ${delayMs} ms")
            kotlinx.coroutines.delay(delayMs)
            val args = lastConnectArgs ?: break
            if (_connectionState.value == ConnectionState.Connected ||
                _connectionState.value == ConnectionState.Connecting
            ) {
                Log.i(TAG, "auto-reconnect: state already ${_connectionState.value}, bailing")
                return
            }

            // Scan for the camera's current IP before every attempt.
            // When the camera drops and rejoins the phone's hotspot it
            // may receive a new DHCP address. Without this refresh,
            // connect() fast-path always tries the stale IP from the
            // last session and fails silently — the 6 retry attempts all
            // hit the wrong host and the user sees "reconnecting…" until
            // it gives up. By rescanning first we hand connect() the live
            // address, so even attempt #1 can fast-path to success.
            Log.i(TAG, "auto-reconnect: scanning for camera before attempt #${attempt + 1}")
            runCatching { performOneScan() }.onFailure {
                Log.w(TAG, "auto-reconnect: scan failed", it)
            }
            // Match by MAC — the camera's MAC never changes even when
            // DHCP assigns a new IP after a reconnect. If the scan found
            // a camera whose MAC matches the one we last paired with, use
            // its current IP. Fall back to the stale args IP when the
            // scan finds nothing (camera still coming online — connect()
            // will fall through to SSDP+subnet discovery inside
            // raceDiscovery which will wait up to 60s for it).
            val knownMac = args.cameraMac?.lowercase()
            val freshIp = if (knownMac != null) {
                _availableCameras.value
                    .firstOrNull { it.mac.lowercase() == knownMac }
                    ?.ip
                    ?: args.cameraIp
            } else {
                _availableCameras.value.firstOrNull()?.ip ?: args.cameraIp
            }
            if (freshIp != args.cameraIp) {
                Log.i(TAG, "auto-reconnect: camera (mac=$knownMac) moved to $freshIp (was ${args.cameraIp})")
            }

            // Look up the persisted profile keyed by the MAC of the
            // camera we last paired with. The fast path in connect()
            // tries this profile's (lastIp, hostGuid) BEFORE discovery.
            val cachedProfile = args.cameraMac?.let { mac ->
                runCatching { preferences.cameraProfile(mac) }.getOrNull()
            }
            if (cachedProfile != null) {
                Log.i(TAG, "auto-reconnect: using cached profile mac=${cachedProfile.cameraMac} " +
                    "ip=${cachedProfile.lastIp}")
            }
            val result = connect(freshIp, args.hostName, preferredProfile = cachedProfile)
            if (result is ConnectResult.Success) {
                Log.i(TAG, "auto-reconnect: SUCCESS after ${attempt + 1} attempts")
                return
            }
            attempt++
        }
        if (attempt >= MAX_AUTO_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "auto-reconnect: gave up after $attempt attempts — " +
                "user needs to rejoin camera Wi-Fi manually")
            _lastError.value = "Camera Wi-Fi dropped. Tap Connect or select the camera below."
            // Clear lastConnectArgs so the user MUST tap Connect to retry.
            lastConnectArgs = null
            // Connection is genuinely lost (we won't auto-reconnect any
            // further). Wipe per-session caches so the next user-initiated
            // Connect starts clean — matches the user requirement that
            // caches die "due to connection lost".
            wipeSessionCaches()
        }
    }

    /**
     * Capture events from the active [CaptureCoordinator], if any. Replays the
     * latest event for new collectors so the UI status pill picks up the
     * current state on recomposition.
     */
    val captureEvents: SharedFlow<CaptureEvent>?
        get() = coordinator?.events

    /**
     * Build a [SafCaptureTarget] over the user's currently-armed folder Uri.
     * Returns null if no folder is armed or the Uri doesn't resolve.
     *
     * Each session lands in a subdirectory named
     * `{sanitizedModelName}-{yyyyMMdd}/` inside the armed folder. If today's
     * folder already exists (e.g. user reconnected later the same day), it's
     * reused — no duplicate folders, and per-file collision suffixing in
     * [SafCaptureTarget] still handles same-name reshoots.
     */
    private fun makeTargetForArmedFolder(): SafCaptureTarget? {
        // Prefer the per-session "armed" folder (long-press on a tile in
        // the Canon Sync home), but fall back to the app's global
        // saveFolderUri so live remote shooting works without forcing
        // the user to long-press anything. The app default is set on
        // the main Settings screen and is what every other download
        // pathway uses too.
        val armed = _armedFolderUri.value
        val fallback: Uri? = settingsManager.settingsState.value
            .saveFolderUri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "content" }
        val uri = armed ?: fallback ?: return null
        val sub = ensureSessionSubfolder(uri) ?: run {
            Log.w(TAG, "makeTargetForArmedFolder: subfolder unavailable, " +
                "falling back to root output dir")
            return SafCaptureTarget.forDirectory(context, uri)
        }
        Log.i(TAG, "makeTargetForArmedFolder: session folder = '${sessionSubfolderName()}'")
        return SafCaptureTarget.forDirectory(context, sub)
    }

    /**
     * Force the [CaptureCoordinator] to be (re)built. Used by the
     * Live Remote Shooting screen on mount — without this, shots
     * taken via the remote shutter never auto-download because the
     * coordinator was created at connect-time when no folder was
     * armed yet. Safe to call repeatedly; cancels any in-flight
     * coordinator and stands up a new one targeted at the same
     * (possibly settings-derived) folder.
     */
    fun ensureCaptureCoordinator() {
        // Skip if already running and target hasn't changed — there's
        // no cheap way to compare URIs the SafCaptureTarget resolved
        // them from, so just no-op when a live coordinator exists.
        if (coordinator != null && coordinatorJob?.isActive == true) return
        rebuildCoordinatorIfArmed()
    }

    /** Set once the orphan-.part sweep has run this process. */
    @Volatile private var stagingSwept = false

    private fun rebuildCoordinatorIfArmed() {
        coordinatorJob?.cancel()
        coordinator = null

        val activeClient = client ?: return

        // One-shot cleanup of orphan `.part` staging files left by a previous
        // hard-killed transfer (crash / abrupt Wi-Fi drop before discard()).
        // Best-effort, off the hot path; scans the armed/fallback root + its
        // per-session date subfolders. See SafCaptureTarget.sweepStagingFiles.
        if (!stagingSwept) {
            val rootUri = _armedFolderUri.value
                ?: settingsManager.settingsState.value.saveFolderUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?.takeIf { it.scheme == "content" }
            if (rootUri != null) {
                stagingSwept = true
                sessionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val n = runCatching { SafCaptureTarget.sweepStagingFiles(context, rootUri) }
                        .getOrDefault(0)
                    if (n > 0) Log.i(TAG, "rebuildCoordinatorIfArmed: swept $n orphan .part file(s)")
                }
            }
        }

        val target = makeTargetForArmedFolder() ?: return

        // Reconnect path: the camera doesn't remember our prior
        // D11C=3 across PTP session re-opens, so re-apply it whenever
        // we stand a coordinator back up on an armed folder.
        applyArmedCaptureDestination()

        val newCoordinator = CaptureCoordinator(
            client = activeClient,
            source = activeClient.events,
            target = target,
            mode = { _formatMode.value },
        )
        coordinator = newCoordinator
        coordinatorJob = newCoordinator.start(sessionScope)

        sessionScope.launch {
            newCoordinator.events.collect { event -> _captureEvents.value = event }
        }
    }

    /**
     * Re-arm onto a new folder while the session is up. Cancels any in-flight
     * coordinator and stands up a fresh one targeted at the new Uri.
     */
    fun rearm(folderUri: Uri) {
        _armedFolderUri.value = folderUri
        sessionScope.launch { preferences.setArmedFolderUri(folderUri) }
        if (_connectionState.value == ConnectionState.Connected) {
            rebuildCoordinatorIfArmed()
        }
    }

    private suspend fun tearDownSession() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        coordinatorJob?.cancel()
        coordinatorJob = null
        coordinator = null
        propertyControllerJob?.cancel()
        propertyControllerJob = null
        _propertyController = null
        client?.close()
        client = null
        currentCameraModel = null
        sessionStartIsoDate = null
        ssidPinRelease?.invoke()
        ssidPinRelease = null
        // NOTE: do NOT stop reconnectEngine here. The whole point is
        // that the engine OUTLIVES individual PTP/IP teardowns so it
        // can silently re-establish the Wi-Fi link when the camera's
        // radio comes back up. We only stop it in disconnect() (user
        // intent) — see that handler.
        networkRelease?.invoke()
        networkRelease = null
        if (_connectionState.value != ConnectionState.Failed) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ───────────────── Batch download pipeline ───────────────────────
    /**
     * Start the bulk-download pipeline against the working dir Uri.
     *
     *  - Enumerate every photo on the card via `0x9109 EOS_GetObjectInfoEx`.
     *  - Filter by [format] (RAW / JPEG / BOTH) and by skip-existing
     *    (filename match against the destination folder).
     *  - Download each remaining file via chunked `0x9107` + `0x9117`.
     *  - After the initial sweep, idle-poll every 10 s via the existing
     *    EOS event stream; when 0xC181 records arrive, queue them for
     *    download.
     *  - Pause + skip 0x9117 on user-cancel (via `0x9118
     *    EOS_CancelTransfer`).
     *
     * Idempotent — second call while a job is already active is a
     * no-op. Cancelled by [stopBatchDownload] OR session teardown.
     *
     * @param destFolderUri tree Uri (SAF) where files land. Per-camera
     *   session subfolder is added by [SafCaptureTarget] / our
     *   `makeTargetForArmedFolder`-style helper.
     * @param format RAW / JPEG / BOTH — affects which handles are
     *   included in the initial sweep AND new shots.
     */
    /**
     * Optional finalization callback for [startBatchDownload]. Fired
     * after each successful download completes (post-`finalize()`), with
     * the SAF Uri of the saved file and its display name. The
     * Download&Process pipeline uses this to forward each freshly-saved
     * file into the RAW processor without re-walking the destination
     * folder.
     *
     * Called from the download coroutine — keep it lightweight and
     * dispatch heavy work to its own coroutine. Default no-op.
     */
    @Volatile
    private var batchOnFinalized: (suspend (Uri, String) -> Unit)? = null

    fun startBatchDownload(
        destFolderUri: Uri,
        format: BatchFormat,
        onFinalized: (suspend (Uri, String) -> Unit)? = null,
    ) {
        batchOnFinalized = onFinalized
        if (batchJob?.isActive == true) return
        val activeClient = client ?: run {
            _batchState.value = CanonBatchPhase.Error("Camera not connected")
            return
        }
        // Batch routes into the same per-session subfolder as Browse +
        // Live Shooting. Falls back to the raw destFolderUri if the
        // subfolder can't be created (read-only root, SAF rejection).
        val target = runCatching {
            val sub = ensureSessionSubfolder(destFolderUri) ?: destFolderUri
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, sub)
                ?: return@runCatching null
            if (!root.isDirectory || !root.canWrite()) return@runCatching null
            SafCaptureTarget.forDirectory(context, root.uri)
        }.getOrNull()
        if (target == null) {
            _batchState.value = CanonBatchPhase.Error("Working folder unavailable")
            return
        }
        // Snapshot the destination's existing filenames for skip-existing.
        val existing: Set<String> = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, destFolderUri)
                ?.listFiles().orEmpty()
                .mapNotNull { it.name }
                .toHashSet()
        }.getOrNull() ?: emptySet()
        _batchState.value = CanonBatchPhase.Scanning
        batchJob = sessionScope.launch {
            try {
                val all = runCatching { activeClient.listCameraObjects() }
                    .getOrNull() ?: emptyList()
                val filtered = all.filter { obj ->
                    when (format) {
                        BatchFormat.RAW -> obj.format in com.RAZStudio.StudioRoom
                            .feature.canon_sync.net.PtpIpConstants.RAW_FORMATS
                        BatchFormat.JPEG -> obj.format ==
                            com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants.FMT_JPEG
                        BatchFormat.BOTH -> true
                    }
                }
                val todo = filtered.filter { it.filename !in existing }
                val total = filtered.size
                val initiallySkipped = total - todo.size
                Log.i(TAG, "startBatchDownload: total=$total skipped=$initiallySkipped " +
                    "queued=${todo.size}")
                var done = initiallySkipped
                for ((idx, obj) in todo.withIndex()) {
                    if (!isActive) break
                    _batchState.value = CanonBatchPhase.Running(
                        currentFilename = obj.filename,
                        currentHandle = obj.handle,
                        currentBytes = 0,
                        currentBytesTotal = obj.sizeBytes,
                        doneCount = done,
                        skippedCount = initiallySkipped,
                        totalCount = total,
                    )
                    val allocation = runCatching {
                        target.open(
                            requestedFilename = obj.filename,
                            mimeType = guessMimeTypeFromFilename(obj.filename),
                        )
                    }.getOrNull()
                    if (allocation == null) {
                        Log.w(TAG, "startBatchDownload: SAF open failed for '${obj.filename}'")
                        continue
                    }
                    val outcome = runCatching {
                        activeClient.streamObjectViaPartial(
                            handle = obj.handle,
                            destination = allocation.sink,
                            advertisedTotalBytes = obj.sizeBytes,
                            onProgress = { written, _ ->
                                _batchState.value = CanonBatchPhase.Running(
                                    currentFilename = obj.filename,
                                    currentHandle = obj.handle,
                                    currentBytes = written,
                                    currentBytesTotal = obj.sizeBytes,
                                    doneCount = done,
                                    skippedCount = initiallySkipped,
                                    totalCount = total,
                                )
                            },
                        )
                    }.getOrNull()
                    if (outcome is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult.Success) {
                        val finalizedUri = runCatching { allocation.finalize() }.getOrNull()
                        done++
                        // Fire the per-file finalize callback so the
                        // Download&Process pipeline can pick this file
                        // up immediately (no folder rescan needed).
                        if (finalizedUri != null) {
                            runCatching { batchOnFinalized?.invoke(finalizedUri, obj.filename) }
                        }
                    } else {
                        runCatching { allocation.discard() }
                        // Best-effort cancel: tell the camera we're not
                        // sending TransferComplete for this handle.
                        runCatching { activeClient.cancelTransfer(obj.handle) }
                        Log.w(TAG, "startBatchDownload: failed '${obj.filename}': $outcome")
                    }
                }
                if (!isActive) return@launch
                // Initial sweep complete — drop into watching mode.
                _batchState.value = CanonBatchPhase.Watching(
                    doneCount = done,
                    skippedCount = initiallySkipped,
                )
                // Idle poll: every 10 s rescan the card and pull any new
                // handles. The camera's 0x9109 enumeration is the
                // simplest "new photos?" check; the alternative is the
                // 0x9116 event poll but that needs a continuously
                // running drain. Refresh-on-tick is cheap (~1 round-trip).
                while (isActive) {
                    kotlinx.coroutines.delay(BATCH_IDLE_POLL_INTERVAL_MS)
                    if (!isActive) break
                    val refreshed = runCatching { activeClient.listCameraObjects() }
                        .getOrNull() ?: continue
                    val existingNames: Set<String> = runCatching {
                        androidx.documentfile.provider.DocumentFile
                            .fromTreeUri(context, destFolderUri)
                            ?.listFiles().orEmpty()
                            .mapNotNull { it.name }
                            .toHashSet()
                    }.getOrNull() ?: emptySet()
                    val fresh = refreshed.filter { obj ->
                        obj.filename !in existingNames && when (format) {
                            BatchFormat.RAW -> obj.format in com.RAZStudio.StudioRoom
                                .feature.canon_sync.net.PtpIpConstants.RAW_FORMATS
                            BatchFormat.JPEG -> obj.format ==
                                com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants.FMT_JPEG
                            BatchFormat.BOTH -> true
                        }
                    }
                    if (fresh.isEmpty()) continue
                    Log.i(TAG, "startBatchDownload: ${fresh.size} new photo(s) detected; pulling")
                    val newTotal = (refreshed.size).also { /* recomputed */ }
                    for (obj in fresh) {
                        if (!isActive) break
                        _batchState.value = CanonBatchPhase.Running(
                            currentFilename = obj.filename,
                            currentHandle = obj.handle,
                            currentBytes = 0,
                            currentBytesTotal = obj.sizeBytes,
                            doneCount = done,
                            skippedCount = initiallySkipped,
                            totalCount = newTotal,
                        )
                        val allocation = runCatching {
                            target.open(
                                requestedFilename = obj.filename,
                                mimeType = guessMimeTypeFromFilename(obj.filename),
                            )
                        }.getOrNull() ?: continue
                        val out = runCatching {
                            activeClient.streamObjectViaPartial(
                                handle = obj.handle,
                                destination = allocation.sink,
                                advertisedTotalBytes = obj.sizeBytes,
                                onProgress = { written, _ ->
                                    _batchState.value = CanonBatchPhase.Running(
                                        currentFilename = obj.filename,
                                        currentHandle = obj.handle,
                                        currentBytes = written,
                                        currentBytesTotal = obj.sizeBytes,
                                        doneCount = done,
                                        skippedCount = initiallySkipped,
                                        totalCount = newTotal,
                                    )
                                },
                            )
                        }.getOrNull()
                        if (out is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult.Success) {
                            val finalizedUri = runCatching { allocation.finalize() }.getOrNull()
                            done++
                            if (finalizedUri != null) {
                                runCatching { batchOnFinalized?.invoke(finalizedUri, obj.filename) }
                            }
                        } else {
                            runCatching { allocation.discard() }
                            runCatching { activeClient.cancelTransfer(obj.handle) }
                        }
                    }
                    _batchState.value = CanonBatchPhase.Watching(
                        doneCount = done,
                        skippedCount = initiallySkipped,
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "startBatchDownload: threw", t)
                _batchState.value = CanonBatchPhase.Error(t.message ?: "Batch failed")
            }
        }
    }

    /**
     * Stop the batch downloader. Idempotent. Resets state to Idle.
     * If a transfer is in flight, [CanonWifiClient.cancelTransfer] is
     * fired from a fresh coroutine on [sessionScope] so the cancel
     * opcode actually reaches the wire (mirroring the LV teardown
     * trick).
     */
    fun stopBatchDownload() {
        val toCancel = batchJob
        batchJob = null
        toCancel?.cancel()
        // Cancel the processing pipeline too so it tears down with
        // the same Stop button. The coordinator is idempotent — safe
        // to stop even if it never started.
        downloadAndProcessCoordinator.stop()
        batchOnFinalized = null
        // The state-snapshot at cancellation time may carry an
        // in-flight handle. Fire 0x9118 for it best-effort.
        val state = _batchState.value
        if (state is CanonBatchPhase.Running) {
            val activeClient = client
            if (activeClient != null) {
                sessionScope.launch {
                    runCatching { toCancel?.join() }
                    runCatching { activeClient.cancelTransfer(state.currentHandle) }
                }
            }
        }
        _batchState.value = CanonBatchPhase.Idle
    }

    /** Processing-pipeline state for the Download & Process flow. */
    val processingState: StateFlow<DownloadAndProcessCoordinator.ProcessPhase>
        get() = downloadAndProcessCoordinator.state

    /**
     * Run [startBatchDownload] AND simultaneously process each
     * finalized file through the RAW pipeline. The processor uses
     * the same `RawBatchPrefs`-based settings as RAWEditor's batch UI,
     * so the user's last preset / format / dimensions carry over.
     *
     * Read-ahead is exactly one file ahead — see
     * [DownloadAndProcessCoordinator] for the channel-based throttle.
     *
     * Output of the processed file lands in the same [destFolderUri]
     * as the downloaded original. Both files coexist in the folder
     * (original RAW + processed export — distinguishable by extension).
     */
    fun startBatchDownloadAndProcess(
        destFolderUri: Uri,
        format: BatchFormat,
        perFileContext: com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawBatchProcessor.PerFileContext,
        /**
         * When true, every existing RAW (or JPG in JPEG-only mode) already
         * sitting in [destFolderUri] is enqueued for processing on start.
         * Useful for re-processing yesterday's shoot, or for the common
         * case where the download already happened so the skip-existing
         * filter leaves nothing for the live pipeline to do.
         *
         * Per format, what gets re-enqueued:
         *  - [BatchFormat.RAW]  → every CR2/CR3/...
         *  - [BatchFormat.BOTH] → every RAW; the JPG sibling is skipped
         *    (same rule as the live `onFinalized` filter below).
         *  - [BatchFormat.JPEG] → every JPG/JPEG.
         */
        processExisting: Boolean = false,
    ) {
        downloadAndProcessCoordinator.start(perFileContext)
        if (processExisting) {
            sessionScope.launch {
                val files = runCatching {
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(context, destFolderUri)
                        ?.listFiles().orEmpty()
                }.getOrNull().orEmpty()
                var enqueued = 0
                for (f in files) {
                    val name = f.name ?: continue
                    val ext = name.substringAfterLast('.', "")
                        .lowercase(java.util.Locale.ROOT)
                    val isJpg = ext == "jpg" || ext == "jpeg"
                    val isRaw = ext in setOf("cr2", "cr3", "crw", "nef", "arw", "dng", "raf", "rw2", "orf", "pef")
                    val keep = when (format) {
                        BatchFormat.RAW -> isRaw
                        BatchFormat.JPEG -> isJpg
                        BatchFormat.BOTH -> isRaw
                    }
                    if (keep) {
                        downloadAndProcessCoordinator.enqueue(f.uri, name)
                        enqueued++
                    }
                }
                Log.i(TAG, "processExisting: enqueued $enqueued file(s) from $destFolderUri")
            }
        }
        // Use the existing downloader; route each finalized file into
        // the coordinator's channel via the callback hook.
        startBatchDownload(
            destFolderUri = destFolderUri,
            format = format,
            onFinalized = { uri, filename ->
                // In RAW+JPG mode the downloader finalises both siblings of
                // each shot. Only the RAW needs to go through the v3 RAW
                // pipeline — feeding the JPG sibling to LibRaw either fails
                // outright or wastes work re-encoding an already-baked
                // image. In JPEG-only mode we still let the JPG through
                // because there is no RAW to prefer.
                val ext = filename.substringAfterLast('.', "")
                    .lowercase(java.util.Locale.ROOT)
                val isJpg = ext == "jpg" || ext == "jpeg"
                if (format == BatchFormat.BOTH && isJpg) {
                    Log.i(TAG, "skip processing JPG sibling '$filename' (RAW will be processed)")
                } else {
                    downloadAndProcessCoordinator.enqueue(uri, filename)
                }
            },
        )
    }

    private fun describeConnectFailure(result: ConnectResult): String = when (result) {
        is ConnectResult.Success -> ""
        is ConnectResult.Failure.SocketUnreachable -> {
            // The JVM's "Software caused connection abort" / "Read timed out"
            // messages aren't user-friendly. Disambiguate by content:
            //  - "abort"   → camera dropped TCP (legacy bodies do this when
            //                the user doesn't press SET within ~30s)
            //  - "timed out" → camera didn't respond at all (firmware
            //                  stalled, AP died, or we beat it to the punch
            //                  before pairing menu was active)
            val cause = result.cause.lowercase()
            when {
                "abort" in cause ->
                    "Camera cancelled pairing. Make sure you press SET on the camera body when " +
                        "the 'Connect this smartphone?' prompt appears, then tap Connect again."
                "timed out" in cause ->
                    // The TCP scan saw port 15740 open but InitCommand
                    // never got a reply within 5 s. On EOS bodies (6D /
                    // 7D / 70D / 80D / 5D-III / R-series with paired
                    // slot) this is almost always Err 11 — the camera
                    // has a saved EOS Utility slot bound to a SPECIFIC
                    // host MAC, but Android randomized the hotspot MAC
                    // since pairing so the camera silently rejects the
                    // handshake. Two fixes:
                    //   1. Phone Settings → Hotspot → MAC address type
                    //      → "Use device MAC" (not randomized), then
                    //      toggle hotspot off/on.
                    //   2. On camera: Wi-Fi function → EOS Utility →
                    //      Confirm settings → Delete, then re-pair
                    //      fresh.
                    "Camera shows Err 11 (paired host not found). Two fixes:\n" +
                        "  • Phone Settings → Hotspot → set MAC type to " +
                        "'Use device MAC' (not Randomized), toggle hotspot off/on.\n" +
                        "  • OR on camera: Wi-Fi → EOS Utility → Delete the saved " +
                        "slot and re-pair from scratch."
                else -> "Cannot reach camera: ${result.cause}"
            }
        }
        is ConnectResult.Failure.InitRejected -> result.reasonText
        is ConnectResult.Failure.PtpResponseError ->
            "Camera rejected op 0x%04X with code 0x%04X".format(result.operationCode, result.responseCode)
        is ConnectResult.Failure.ProtocolError -> result.message
    }

    /**
     * Background scan loop. Runs every [SCAN_LOOP_INTERVAL_MS] for as
     * long as the loop is alive, populating [_availableCameras] from
     * the ARP table + a brief SSDP listen. Multiple calls coalesce
     * onto a single loop; cancelling tears it down.
     *
     * Caller is responsible for deciding WHEN to start it — typically
     * the UI starts it when the screen mounts AND the phone is in
     * hotspot / no-internet state AND we're not already Connected.
     */
    fun startCameraScanLoop() {
        if (scanLoopJob?.isActive == true) return
        scanLoopJob = sessionScope.launch {
            try {
                while (isActive) {
                    _isScanning.value = true
                    runCatching { performOneScan() }
                        .onFailure { Log.w(TAG, "scan tick threw", it) }
                    _isScanning.value = false
                    kotlinx.coroutines.delay(SCAN_LOOP_INTERVAL_MS)
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** Stop the background scan loop. Idempotent. */
    fun stopCameraScanLoop() {
        scanLoopJob?.cancel()
        scanLoopJob = null
        _isScanning.value = false
    }

    /**
     * One-shot scan, public so a "Scan now" button in the UI can
     * force a refresh without waiting for the next loop tick. Safe to
     * call concurrently with the loop — both update the same flow.
     */
    suspend fun scanForCanonNow() {
        _isScanning.value = true
        runCatching { performOneScan() }
            .onFailure { Log.w(TAG, "manual scan threw", it) }
        _isScanning.value = false
    }

    /**
     * One scan = ARP read (immediate) + best-effort SSDP listen for
     * 1.5 s (cheap; lets cameras self-advertise their friendly name).
     * Results are merged into [_availableCameras] by IP — SSDP rows
     * win on `friendlyName` because they carry the camera model;
     * ARP-only rows fall back to a generic "Canon" placeholder.
     */
    private suspend fun performOneScan() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // /proc/net/arp is blocked by SELinux for app-context reads on
        // Android 10+. Skip the prime + ARP read in that case and go
        // straight to a PTP-port scan, which is the only reliable
        // detection path. We still call arpScan in case we're on a
        // pre-Android-10 device or a custom build that allows the
        // read; harmless when it returns empty.
        // Probe the Wi-Fi layer for the DHCP-known subnet / gateway /
        // SSID. When this returns null we fall back to the legacy
        // interface-walking path. When it succeeds, we feed the
        // snapshot into the informed scanner which probes the
        // gateway first and skips our own IP — much faster than the
        // blind 1..254 sweep.
        val wifiSnap = com.RAZStudio.StudioRoom.feature.canon_sync.net
            .WifiInfoProbe.snapshot(context, TAG)
        com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonOui
            .primeArpCache(TAG)
        var arpHits = com.RAZStudio.StudioRoom.feature.canon_sync.net
            .CanonOui.arpScan(TAG)
        if (arpHits.isEmpty()) {
            // Always scan all private-range interfaces via NetworkInterface
            // (the no-snapshot path). This is critical when the phone is
            // simultaneously connected to a home router AND the camera's
            // Wi-Fi AP — WifiManager.dhcpInfo only returns the primary
            // association (home router), so the informed/snapshot path
            // would scan the wrong subnet entirely and miss the camera.
            // The no-snapshot path walks every private-range IP on every
            // interface including the camera AP subnet (e.g. 192.168.108.x).
            val ptpIps = com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonOui
                .ptpPortScan(timeoutMs = 200, tag = TAG)
            arpHits = ptpIps.map {
                com.RAZStudio.StudioRoom.feature.canon_sync.net
                    .CanonOui.ArpHit(it, "??:??:??:??:??:??")
            }
        }
        // SSDP — short discoverFirst() call (~1.5 s ceiling) so the
        // scan tick stays well under 30 s budget. The discoverer
        // internally calls blocking DatagramSocket.receive() which
        // MUST not run on Main.
        val ssdpAd = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                WftUpnpDiscoverer(
                    context = context,
                    // null = let the discoverer use the process default
                    // network. Good enough for a non-connected scan tick.
                    network = null,
                ).discoverFirst(timeoutMs = 1_500L)
            }
        }.getOrNull()
        val merged = LinkedHashMap<String, com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera>()
        for (hit in arpHits) {
            merged[hit.ip] = com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera(
                ip = hit.ip,
                mac = hit.matchedMac,
                friendlyName = friendlyNameForMac(hit.matchedMac),
                seenAtMs = now,
            )
        }
        if (ssdpAd != null) {
            val mac = merged[ssdpAd.ip]?.mac
                ?: arpHits.firstOrNull { it.ip == ssdpAd.ip }?.matchedMac
                ?: "??:??:??:??:??:??"
            merged[ssdpAd.ip] = com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.DetectedCamera(
                ip = ssdpAd.ip,
                mac = mac,
                friendlyName = ssdpAd.friendlyName.ifBlank { friendlyNameForMac(mac) },
                seenAtMs = now,
            )
            // Camera-initiated pairing: camera broadcast detected via SSDP.
            // Auto-connect immediately — no dialog, no user tap needed. This
            // matches EOS Utility behaviour: the camera's "Press SET" prompt
            // on its own LCD is the only user interaction required.
            // Also fire when state is Failed AND lastConnectArgs is set —
            // this means the drop was not user-initiated and the scan loop
            // found the camera at its (possibly new) IP before the
            // auto-reconnect loop's next attempt. Don't fire during
            // Connecting (auto-reconnect is already in flight).
            val curState = _connectionState.value
            val isAutoReconnectable = curState == ConnectionState.Disconnected ||
                (curState == ConnectionState.Failed && lastConnectArgs != null)
            if (isAutoReconnectable && autoReconnectJob?.isActive != true) {
                Log.i(TAG, "performOneScan: auto-connecting to ${ssdpAd.friendlyName} (${ssdpAd.ip})")
                sessionScope.launch { connect(cameraIp = ssdpAd.ip, hostName = HOST_NAME) }
            }
        }
        _availableCameras.value = merged.values.toList()
        Log.i(TAG, "performOneScan: arp=${arpHits.size} ssdp=${if (ssdpAd != null) 1 else 0} → ${merged.size} camera(s)")
    }

    /**
     * Map an OUI to a friendly name. Currently a single label "Canon
     * Camera"; future work could split EOS / PowerShot / printer.
     */
    private fun friendlyNameForMac(mac: String): String = "Canon camera"

    companion object {
        /** logcat filter: `adb logcat -s CanonSync:*` */
        private const val TAG = "CanonSync"

        /** Hostname sent to the camera in INIT_COMMAND_REQUEST — shown on camera LCD. */
        private const val HOST_NAME = "RAZStudio"

        /** Rescan cadence while the phone hotspot is up and we aren't connected. */
        private const val SCAN_LOOP_INTERVAL_MS = 30_000L

        /**
         * Native Live-View image dimensions reported by the 6D's
         * `OC_EOS_GET_VIEW_FINDER_DATA (0x9153)`. Only used internally
         * by this repo for LV-preview tap normalisation; sensor-coord
         * scaling for AF-frame writes uses [PtpIpConstants.SENSOR_6D_*].
         */
        private const val EVF_NATIVE_WIDTH = 1024
        private const val EVF_NATIVE_HEIGHT = 680

        /**
         * Fan-out for [scanForPtpIpOnHotspotSubnet]. 16 concurrent
         * Socket.connect probes against a /24 turn a worst-case 60 s
         * serial scan into a ~4 s parallel one. Dispatchers.IO's
         * default 64-thread pool can absorb this comfortably.
         */
        private const val PARALLEL_PROBE_FANOUT = 16

        /**
         * Big-thumbnail LRU window: `current + 4 left + 4 right`.
         *
         * The user spec said ±2 preload / ±3 eviction, but adb showed
         * casual back-and-forth swiping (touching 5+ distinct photos
         * in <10 s) was evicting the head of the strip and then
         * re-downloading the same multi-MB JPEG when the user paged
         * back. Bumping the LRU to 9 absorbs typical pan-and-return
         * patterns without changing the visible preload window —
         * neighbours beyond ±[PREVIEW_NEIGHBOR_WINDOW] still aren't
         * prefetched, they just stay cached IF the user happens to
         * land on them and then swipes away briefly.
         *
         * 9 × ~4 MB = ~36 MB worst case in cacheDir; reclaimed on
         * disconnect or sessions-died via [wipeSessionCaches].
         */
        private const val PREVIEW_CACHE_CAPACITY = 9

        /** How many neighbours either side of the current page to preload. */
        const val PREVIEW_NEIGHBOR_WINDOW = 2

        /**
         * Cap on automatic reconnect attempts before giving up. The 6D's
         * Wi-Fi chip occasionally goes fully silent (no AP at all) when
         * it overheats or runs low on battery — no software can recover
         * from that state until the user power-cycles the camera and/or
         * manually rejoins the AP in Android Wi-Fi settings.
         *
         * 5 attempts × backoff (2/4/8/15/30 s) = ~60 s total before
         * surfacing "rejoin Wi-Fi manually" — short enough that the user
         * doesn't sit watching a frozen UI, long enough to recover from
         * transient drops without their intervention.
         */
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 5

        /**
         * Idle-watch cadence for the batch download screen. Every 10 s
         * after the initial card sweep completes we re-enumerate to
         * spot new shots. Researched against community 6D PTP/IP
         * guidance: faster than 2 Hz (500 ms) hangs older bodies;
         * 10 s is comfortably idle-friendly + matches the user's spec.
         */
        private const val BATCH_IDLE_POLL_INTERVAL_MS: Long = 10_000
    }
}
