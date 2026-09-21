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

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyCameraRemoteApi
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyModelNames
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyUsbImporter
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyWifiConnector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screen logic for **Sony Sync** (Decompose child, assisted-injected by
 * feature:root like CanonSyncComponent).
 *
 * v1 transport = the Sony **Camera Remote API** (ScalarWebAPI) pull path:
 *   1. Bind this process to the already-joined camera Wi-Fi
 *      (`DIRECT-*:ILCE-7M2`, see docs/SONY_SYNC.md) — `SonyWifiConnector`.
 *   2. SSDP-discover the camera + fetch its service endpoints.
 *   3. Browse still images via `avContent` and download them into the user's
 *      Default Output (SAF) folder — `SonyCameraRemoteApi`.
 *
 * All steps stream progress into [logLines] AND logcat (TAG below) so a stuck
 * connection is diagnosable on-device. If the body doesn't expose `avContent`
 * browsing (older Alphas only allow remote shooting), that is reported clearly
 * and the user is pointed at the camera's Send-to-Smartphone push instead.
 */
class SonySyncComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    private val _step = MutableStateFlow(SonyConnectionStep.Idle)
    val step: StateFlow<SonyConnectionStep> = _step.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log.asStateFlow()

    /** (downloaded, total) — total is 0 until enumeration completes. */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Terminal outcome of the last run — drives the success/failure banner on the
    // Sony Sync page (the user wants a clear "pulled N to <folder>" or "failed").
    private val _result = MutableStateFlow<SonyResult?>(null)
    val result: StateFlow<SonyResult?> = _result.asStateFlow()

    // Import resolution, mirroring PlayMemories' "Image Size for Importing".
    // Default Original (full-size JPEG); 2M is the smaller resized copy.
    private val _syncPrefs = com.RAZStudio.StudioRoom.feature.sony_sync.data.cloud.CloudPrefs(context)
    private val _importSize = MutableStateFlow(
        SonyCameraRemoteApi.ImportSize.entries.getOrElse(_syncPrefs.importSizeOrdinal) { SonyCameraRemoteApi.ImportSize.ORIGINAL }
    )
    val importSize: StateFlow<SonyCameraRemoteApi.ImportSize> = _importSize.asStateFlow()
    fun setImportSize(size: SonyCameraRemoteApi.ImportSize) {
        _importSize.value = size
        _syncPrefs.importSizeOrdinal = size.ordinal
    }

    // How to reach the camera. AUTO (default) = one SSDP sweep decides Wi-Fi
    // older/newer (ScalarWebAPI → newer, MediaServer → older) so the user needn't
    // know their body's generation. WIFI_OLDER = DLNA MediaServer only (A7 II,
    // α6000, early α7, RX compacts — Send-to-Smartphone push). WIFI_NEWER =
    // ScalarWebAPI first (Ctrl-w-Smartphone remote), DLNA fallback (A7 III/R III/
    // α9 etc.). USB_MASS_STORAGE = OTG cable, camera in Mass Storage mode — reads
    // the whole card over libaums (originals incl. RAW). Same camera can expose
    // either Wi-Fi path depending on mode/firmware, so WIFI_NEWER still falls back.
    private val _connectionType = MutableStateFlow(
        SonyConnectionType.entries.getOrElse(_syncPrefs.connectionTypeOrdinal) { SonyConnectionType.AUTO }
    )
    val connectionType: StateFlow<SonyConnectionType> = _connectionType.asStateFlow()
    fun setConnectionType(t: SonyConnectionType) {
        _connectionType.value = t
        _syncPrefs.connectionTypeOrdinal = t.ordinal
    }

    // ── Camera Remote (USB PC-Remote live shooting) sub-screen ──────────────
    // A full-screen pane inside this feature (no separate nav route) that opens
    // the camera over PTP and drives it live. See docs/SONY_PC_REMOTE.md.
    val remote: SonyCameraRemoteController by lazy {
        SonyCameraRemoteController(context, componentScope, ioDispatcher).also { r ->
            // Every snap is teed to: (a) save to the phone + auto Gallery project,
            // and (b) the cloud uploader IF the cloud flow has been opened (no-op
            // there unless a live upload is running). Setting it here (not only in
            // the cloud lazy) means plain Camera-Remote shooting also saves.
            r.onCaptured = { name, bytes ->
                componentScope.launch(ioDispatcher) { saveCapturedToOutput(name, bytes) }
                if (cloudCreated) cloud.uploadCaptured(name, bytes)
            }
        }
    }
    private val _remoteActive = MutableStateFlow(false)
    val remoteActive: StateFlow<Boolean> = _remoteActive.asStateFlow()
    fun enterRemote() { _remoteActive.value = true; remote.connect() }
    fun exitRemote() { remote.disconnect(); _remoteActive.value = false }

    // ── Live upload to cloud (Google Drive first) ───────────────────────────
    // Each snap from the Camera Remote is routed here → resized to ~2 MP →
    // uploaded to the shared folder while the upload page is Started.
    @Volatile private var cloudCreated = false
    val cloud: SonyCloudController by lazy {
        cloudCreated = true
        SonyCloudController(context, componentScope, ioDispatcher)
    }
    private val _cloudSettingsActive = MutableStateFlow(false)
    val cloudSettingsActive: StateFlow<Boolean> = _cloudSettingsActive.asStateFlow()
    private val _cloudUploadActive = MutableStateFlow(false)
    val cloudUploadActive: StateFlow<Boolean> = _cloudUploadActive.asStateFlow()
    fun openCloudSettings() { cloud; _cloudSettingsActive.value = true }
    fun closeCloudSettings() { _cloudSettingsActive.value = false }
    fun openCloudUpload() { cloud; _cloudUploadActive.value = true }
    fun closeCloudUpload() { cloud.stop(); _cloudUploadActive.value = false }

    private var job: Job? = null

    private fun log(line: String) {
        Log.i(TAG, line)
        _log.update { (it + line).takeLast(200) }
    }

    /**
     * The main action: connect to the already-joined camera Wi-Fi, discover the
     * Camera Remote API, list stills, and download them to Default Output.
     * Idempotent — ignores taps while already running.
     */
    fun connectAndDownload() {
        if (_running.value) return
        _running.value = true
        _log.value = emptyList()
        _progress.value = 0 to 0
        _result.value = null
        val connector = SonyWifiConnector(context)
        job = componentScope.launch(ioDispatcher) {
            // USB Mass Storage path: no Wi-Fi bind / DLNA — read the card directly.
            if (_connectionType.value == SonyConnectionType.USB_MASS_STORAGE) {
                try {
                    importFromUsb()
                } catch (e: Exception) {
                    log("USB error: ${e.message}")
                    _result.value = SonyResult.Failed("Couldn't read the camera over USB.")
                } finally {
                    _running.value = false
                }
                return@launch
            }
            var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
            try {
                // 1. Bind to the camera Wi-Fi.
                _step.value = SonyConnectionStep.JoiningSoftAp
                val ssid = connector.currentSsid()
                log("Current Wi-Fi: ${ssid ?: "(unknown — grant location for SSID)"}")
                val network = connector.findCurrentCameraNetwork()
                if (network == null) {
                    log("Not on a camera Wi-Fi. Join the camera's network " +
                        "(DIRECT-…:ILCE-7M2) from the camera's Send-to-Smartphone / " +
                        "Ctrl-with-Smartphone screen, then try again.")
                    _result.value = SonyResult.Failed("Join the camera's Wi-Fi first.")
                    _step.value = SonyConnectionStep.Idle
                    return@launch
                }
                log("Bound to camera network${ssid?.let { " ($it)" } ?: ""}.")
                multicastLock = connector.acquireMulticastLock()

                // 2. Discover + enumerate stills.
                _step.value = SonyConnectionStep.Discovering
                val api = SonyCameraRemoteApi(network) { log(it) }
                // PRIMARY: DLNA MediaServer — the path the A7 II (and most Alpha
                //   bodies) use in "Send to Smartphone". SECONDARY: ScalarWebAPI —
                //   only for bodies in "Ctrl w/ Smartphone" that expose avContent.
                var stills: List<SonyCameraRemoteApi.RemoteContent> = emptyList()
                // Non-null on the DLNA push path → drives the XPushList lifecycle
                // (X_TransferStart/Progress/End) so the camera dismisses its spinner.
                var dlnaCd: SonyCameraRemoteApi.ContentDirectory? = null

                // Runs the DLNA MediaServer path; sets dlnaCd + starts the XPushList
                // session when found. Returns the browsed stills (empty if none).
                suspend fun runDlna(): List<SonyCameraRemoteApi.RemoteContent> {
                    val ms = api.discoverMediaServer() ?: run {
                        log("No DLNA MediaServer on this network."); return emptyList()
                    }
                    val cd = api.resolveContentDirectory(ms.locationUrl) ?: run {
                        log("MediaServer found but no ContentDirectory service."); return emptyList()
                    }
                    log("DLNA camera: ${cd.friendlyName ?: "(unnamed)"}")
                    dlnaCd = cd
                    val pushRoot = api.xGetPushRoot(cd)
                    api.xTransferStart(cd)
                    return api.browseStillsDlna(cd, startId = pushRoot ?: "0", size = _importSize.value)
                }
                // Runs the ScalarWebAPI (Camera Remote API) path.
                fun runScalar(): List<SonyCameraRemoteApi.RemoteContent> {
                    val discovery = api.discover() ?: run {
                        log("No ScalarWebAPI on this network."); return emptyList()
                    }
                    val services = api.fetchServices(discovery.locationUrl)
                    log("Camera: ${services.friendlyName ?: "(unnamed)"}")
                    return api.listStills(services).filter { it.isStill || it.originalUrl.isNotBlank() }
                }

                // AUTO resolves to a concrete Wi-Fi path via one SSDP sweep; the
                // manual choices are honoured as-is. USB was handled far above.
                val effectiveType = if (_connectionType.value == SonyConnectionType.AUTO) {
                    val probe = api.autoDetectTransport()
                    when (probe.recommended) {
                        SonyCameraRemoteApi.RecommendedTransport.WIFI_NEWER -> {
                            log("Auto-detect: ScalarWebAPI advertised → using Wi-Fi (newer).")
                            SonyConnectionType.WIFI_NEWER
                        }
                        SonyCameraRemoteApi.RecommendedTransport.WIFI_OLDER -> {
                            log("Auto-detect: DLNA MediaServer advertised → using Wi-Fi (older).")
                            SonyConnectionType.WIFI_OLDER
                        }
                        null -> {
                            log("Auto-detect: camera advertised no known service " +
                                "(${probe.services.ifEmpty { listOf("nothing") }.joinToString()}) " +
                                "— trying newer then older.")
                            SonyConnectionType.WIFI_NEWER
                        }
                    }
                } else {
                    _connectionType.value
                }

                when (effectiveType) {
                    SonyConnectionType.WIFI_OLDER -> {
                        // DLNA MediaServer only (no ScalarWebAPI attempt).
                        log("Connection: Wi-Fi (older) — using DLNA MediaServer.")
                        stills = runDlna()
                    }
                    SonyConnectionType.WIFI_NEWER -> {
                        // ScalarWebAPI first; DLNA fallback if it yields nothing.
                        log("Connection: Wi-Fi (newer) — trying ScalarWebAPI first.")
                        stills = runScalar()
                        if (stills.isEmpty()) {
                            log("ScalarWebAPI returned nothing — falling back to DLNA…")
                            stills = runDlna()
                        }
                    }
                    // AUTO was resolved above; USB is handled before the Wi-Fi flow
                    // (see connectAndDownload). Both are unreachable here but keep
                    // the `when` exhaustive.
                    SonyConnectionType.AUTO, SonyConnectionType.USB_MASS_STORAGE -> Unit
                }
                if (stills.isEmpty()) {
                    log("Connected, but no browsable JPEGs were returned by the camera.")
                    log("Make sure the camera is showing images (playback) and in a " +
                        "Send-to-Smartphone / Ctrl-w-Smartphone session, then retry.")
                    // Close the push session even with nothing to pull, or the
                    // camera keeps spinning.
                    dlnaCd?.let { api.xTransferEnd(it, 0) }
                    _result.value = SonyResult.Failed("No photos to download.")
                    _step.value = SonyConnectionStep.Connected
                    return@launch
                }

                // 4. Resolve the Default Output (SAF) folder.
                val dir = resolveOutputDir()
                if (dir == null) {
                    log("Set a Default Output folder in Settings first — that's where " +
                        "downloaded photos are saved.")
                    dlnaCd?.let { api.xTransferEnd(it, 0) }
                    _result.value = SonyResult.Failed("No output folder set in Settings.")
                    _step.value = SonyConnectionStep.Connected
                    return@launch
                }
                val folderName = dir.name ?: "output folder"
                log("Saving ${stills.size} photo(s) to: $folderName")

                // 5. Download.
                _step.value = SonyConnectionStep.Connected
                _progress.value = 0 to stills.size
                var done = 0
                val savedUris = ArrayList<Uri>()   // for the share-flow preview on success
                for (item in stills) {
                    if (job?.isCancelled == true) break
                    try {
                        val name = ensureJpgName(item.name)
                        val file = dir.createFile("image/jpeg", name)
                        if (file == null) { log("Could not create $name"); continue }
                        context.contentResolver.openOutputStream(file.uri)?.use { os ->
                            val bytes = api.download(item.originalUrl, os)
                            log("Saved $name (${bytes / 1024} KB)")
                        } ?: log("Could not open output for $name")
                        savedUris += file.uri
                    } catch (e: Exception) {
                        log("Failed ${item.name}: ${e.message}")
                    }
                    done++
                    _progress.value = done to stills.size
                    // Report progress to the camera (drives its on-screen count).
                    dlnaCd?.let { api.xTransferProgress(it, stills.size, done) }
                }
                // Close the Sony push session → dismisses the camera spinner.
                // 0 = success; a cancellation reports a non-zero error code.
                dlnaCd?.let { api.xTransferEnd(it, if (job?.isCancelled == true) 1 else 0) }
                log("Done. Downloaded $done of ${stills.size} photo(s).")
                if (savedUris.isNotEmpty()) {
                    _result.value = SonyResult.Success(done, stills.size, folderName)
                    // Straight into the share-flow Image Preview with the pulled
                    // photo(s). Navigation must run on the main thread.
                    withContext(Dispatchers.Main) {
                        onNavigate(Screen.ImagePreview(savedUris.toList()))
                    }
                } else {
                    _result.value = SonyResult.Failed("Download failed.")
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
                _result.value = SonyResult.Failed("Something went wrong.")
            } finally {
                runCatching { multicastLock?.release() }
                connector.release()
                _running.value = false
            }
        }
    }

    /**
     * USB Mass Storage import: detect + log the camera's USB features, request
     * access, then copy every DCIM photo (originals, including RAW) to the
     * Default Output folder. Runs on [ioDispatcher] (libaums does blocking I/O).
     */
    private suspend fun importFromUsb() {
        _step.value = SonyConnectionStep.Discovering
        log("Connection: USB Mass Storage.")
        val importer = SonyUsbImporter(context) { log(it) }
        try {
            val detected = importer.detect()
            if (detected == null) {
                _result.value = SonyResult.Failed("No camera found. Connect it by USB.")
                _step.value = SonyConnectionStep.Idle
                return
            }
            log("Selected: ${detected.displayName} — ${detected.modeLabel}")
            if (!detected.isMassStorage) {
                _result.value = SonyResult.Failed("Set the camera's USB mode to Mass Storage.")
                _step.value = SonyConnectionStep.Connected
                return
            }
            if (!importer.ensurePermission(detected.device)) {
                _result.value = SonyResult.Failed("USB access was denied.")
                _step.value = SonyConnectionStep.Connected
                return
            }
            _step.value = SonyConnectionStep.Connected
            val images = importer.openAndListImages(detected.device)
            if (images.isEmpty()) {
                _result.value = SonyResult.Failed("No photos on the camera.")
                return
            }
            val dir = resolveOutputDir()
            if (dir == null) {
                log("Set a Default Output folder in Settings first.")
                _result.value = SonyResult.Failed("No output folder set — pick one in Settings.")
                return
            }
            val folderName = dir.name ?: "output folder"
            log("Saving ${images.size} file(s) to: $folderName")
            _progress.value = 0 to images.size
            var done = 0
            val savedUris = ArrayList<Uri>()
            for (img in images) {
                if (job?.isCancelled == true) break
                try {
                    val file = dir.createFile(mimeFor(img.name), img.name)
                    if (file == null) { log("Could not create ${img.name}"); continue }
                    context.contentResolver.openOutputStream(file.uri)?.use { os ->
                        val bytes = img.open().use { ins -> ins.copyTo(os) }
                        log("Saved ${img.name} (${bytes / 1024} KB)")
                    } ?: log("Could not open output for ${img.name}")
                    savedUris += file.uri
                } catch (e: Exception) {
                    log("Failed ${img.name}: ${e.message}")
                }
                done++
                _progress.value = done to images.size
            }
            log("Done. Copied $done of ${images.size} file(s).")
            if (savedUris.isNotEmpty()) {
                _result.value = SonyResult.Success(done, images.size, folderName)
                withContext(Dispatchers.Main) {
                    onNavigate(Screen.ImagePreview(savedUris.toList()))
                }
            } else {
                _result.value = SonyResult.Failed("Copy failed.")
            }
        } finally {
            importer.close()
        }
    }

    // MIME for the SAF createFile. RAW/unknown → octet-stream so the provider
    // doesn't append a second extension to the camera's original filename
    // (the SAF mime↔extension trap — see gallery a11y sweep). JPEG/PNG mimes
    // match their extension, so no append there.
    private fun mimeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic", "heif" -> "image/heif"
            "tif", "tiff" -> "image/tiff"
            else -> "application/octet-stream"   // .ARW / .RAW and anything else
        }

    /** Cancel an in-flight run and unbind from the camera network. */
    fun cancel() {
        job?.cancel()
        job = null
        _running.value = false
        _step.value = SonyConnectionStep.Idle
        log("Cancelled.")
    }

    private fun resolveOutputDir(): DocumentFile? {
        val uriStr = settingsManager.settingsState.value.saveFolderUri
            ?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        if (uri.scheme != "content") return null
        val dir = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return null
        return dir.takeIf { it.isDirectory && it.canWrite() }
    }

    private fun ensureJpgName(name: String): String =
        if (name.substringAfterLast('.', "").equals("jpg", true) ||
            name.substringAfterLast('.', "").equals("jpeg", true)
        ) name else "$name.JPG"

    // ── Captured-photo → phone + auto Gallery project ────────────────────────
    // Each snap pulled over PC-Remote is written to
    //   <Custom Output folder>/Sony <Model> <Date>/<name>.jpg
    // and registered into a Gallery Workspace project of the same name (created
    // once per model+date). Model/date come from the JPEG's EXIF.
    // Open saves Sony Remote captures to the selected output folder only.
    private suspend fun saveCapturedToOutput(name: String, jpeg: ByteArray) {
        runCatching {
            val folderName = "Sony " + modelDateFromExif(jpeg)
            val root = resolveOutputDir()
            if (root == null) {
                log("Capture save skipped - set a Custom Output folder in Settings first.")
                return
            }
            val sub = root.findFile(folderName)?.takeIf { it.isDirectory }
                ?: root.createDirectory(folderName)
            if (sub == null) { log("Capture save failed - could not create output folder."); return }
            val jpgName = ensureJpgName(name)
            val file = sub.createFile("image/jpeg", jpgName)
            if (file == null) { log("Capture save failed - could not create file."); return }
            val wrote = context.contentResolver.openOutputStream(file.uri)
                ?.use { it.write(jpeg); true } ?: false
            if (!wrote) { log("Capture save failed - no output stream."); return }
            log("Saved $jpgName")
        }.onFailure { log("Capture save error: ${it.message}") }
    }


    /** "<Model> <yyyy-MM-dd>" from EXIF (falls back to "Camera" / today). */
    private fun modelDateFromExif(jpeg: ByteArray): String {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return runCatching {
            val e = ExifInterface(java.io.ByteArrayInputStream(jpeg))
            val model = SonyModelNames.pretty(e.getAttribute(ExifInterface.TAG_MODEL))
            val date = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.substringBefore(' ')?.replace(':', '-')?.takeIf { it.length == 10 } ?: today
            "$model $date"
        }.getOrDefault("Camera $today")
    }

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): SonySyncComponent
    }

    companion object {
        private const val TAG = "SonySync"
    }
}

/** Connection type selector on the Sony Sync page.
 *  WIFI_OLDER = DLNA MediaServer only (A7 II, α6000, early α7, RX compacts).
 *  WIFI_NEWER = ScalarWebAPI first, DLNA fallback (A7 III/R III, α9, …).
 *  USB_MASS_STORAGE = OTG cable, camera in Mass Storage mode (originals + RAW). */
enum class SonyConnectionType { AUTO, WIFI_OLDER, WIFI_NEWER, USB_MASS_STORAGE }

/** Terminal outcome of a Sony Sync run, for the on-screen result banner. */
sealed interface SonyResult {
    data class Success(val downloaded: Int, val total: Int, val folder: String) : SonyResult
    data class Failed(val reason: String) : SonyResult
}

/** Connection state machine, mapped to the observed A7 II flow. */
enum class SonyConnectionStep {
    Idle,
    WaitingForNfcTap,
    JoiningSoftAp,
    Discovering,
    Connected,
}
