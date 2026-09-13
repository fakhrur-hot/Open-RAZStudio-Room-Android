/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyCameraState
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyModelNames
import com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyPtpUsb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Drives the USB **Camera Remote** page: opens the A7 II over PTP ([SonyPtpUsb]),
 * runs a poll loop (live-view JPEG + full state), and exposes control actions.
 * All PTP calls are serialised through [ptpLock] because the poll loop and user
 * actions share one non-thread-safe USB connection. Owned by the Sony Sync
 * component; uses its coroutine [scope] so it dies with the screen.
 */
class SonyCameraRemoteController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
) {
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ptp = SonyPtpUsb { log(it) }
    private val ptpLock = Mutex()
    private var pollJob: Job? = null
    private var closeJob: Job? = null
    private var lastLiveRetryMin = -1L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _liveView = MutableStateFlow<Bitmap?>(null)
    val liveView: StateFlow<Bitmap?> = _liveView.asStateFlow()

    private val _state = MutableStateFlow(SonyCameraState())
    val state: StateFlow<SonyCameraState> = _state.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log.asStateFlow()

    private fun log(line: String) { Log.i(TAG, line); _log.update { (it + line).takeLast(120) } }

    /** Find + open the camera and start the poll loop. Idempotent. */
    fun connect() {
        if (_connected.value || pollJob?.isActive == true) return
        _status.value = "Connecting…"
        scope.launch(io) {
            // Wait for any in-flight disconnect close to finish FIRST. Otherwise a
            // fast exit→re-enter races: this open can run before disconnect's async
            // close, which then closes the connection we just opened → stuck on
            // "No camera" forever. Joining the close makes re-entry reconnect cleanly.
            closeJob?.join()
            val ok = ptpLock.withLock { openInternal() }
            if (!ok) {
                _status.value = "No camera. Connect the A7 II by OTG in PC Remote mode."
                return@launch
            }
            _connected.value = true
            _status.value = "Connected"
            startPolling()
        }
    }

    /**
     * Close any prior handle, find + (re)open the camera and run the SDIO
     * handshake. Caller MUST hold [ptpLock]. Returns true on success. Shared by
     * the initial [connect] and the poll loop's auto-reconnect so a re-plugged
     * OTG camera recovers without user action.
     */
    private suspend fun openInternal(): Boolean {
        runCatching { ptp.close() }
        val device = findPtpCamera()
        if (device == null) { log("No PTP (still-image) USB device found."); return false }
        val friendly = device.productName?.let { SonyModelNames.pretty(it) } ?: "camera"
        log("Found $friendly (${hex(device.vendorId)}:${hex(device.productId)}).")
        if (!ensurePermission(device)) { _status.value = "USB permission denied."; return false }
        return ptp.open(usbManager, device) && ptp.connectSession()
    }

    fun disconnect() {
        pollJob?.cancel(); pollJob = null
        _connected.value = false
        _liveView.value = null
        _status.value = "Disconnected"
        // Tracked so the next connect() can join it before re-opening (no clobber).
        closeJob = scope.launch(io) { ptpLock.withLock { runCatching { ptp.close() } } }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(io) {
            // Live view and state are DECOUPLED: the loop ticks fast (LIVE tick,
            // ~10 fps) to pull the ~96 KB live-view frame, but the heavier
            // GetAllDevicePropData state read only runs every STATE_INTERVAL_MS.
            // A single loop keeps the two off ONE lock (two jobs would double the
            // contention that made control taps laggy at 90 ms). Session liveness
            // is judged on STATE reads only — a flaky big live-view frame must not
            // trip the reconnect logic (its own give-up counter handles that).
            var stateFails = 0
            var liveFails = 0
            var liveGaveUp = false
            var lastStateMs = 0L
            while (isActive && _connected.value) {
                val liveActive = LIVE_VIEW_ENABLED && !liveGaveUp
                val now = System.currentTimeMillis()
                val doState = !liveActive || now - lastStateMs >= STATE_INTERVAL_MS
                var stateOk = false
                ptpLock.withLock {
                    if (doState) {
                        runCatching {
                            ptp.getAllDeviceProps()?.let {
                                val st = SonyCameraState.from(it)
                                _state.value = st
                                // Drive REC from the camera's real recording status
                                // when known; null (code unverified) → stays false so
                                // the badge never lies. See SonyCameraState.isRecording.
                                st.isRecording?.let { rec -> _recording.value = rec }
                                stateOk = true
                            }
                        }
                        lastStateMs = now
                    }
                    if (liveActive) {
                        val jpeg = runCatching { ptp.getLiveViewJpeg() }.getOrNull()
                        if (jpeg != null) {
                            runCatching {
                                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { _liveView.value = it }
                            }
                            liveFails = 0
                        } else if (++liveFails >= LIVE_GIVEUP) {
                            liveGaveUp = true
                            _liveView.value = null
                            log("Live view unavailable — polling state only.")
                        }
                    }
                }
                if (doState) {
                    if (stateOk) {
                        if (stateFails != 0) { stateFails = 0; _status.value = "Connected" }
                    } else if (++stateFails >= DEAD_AFTER) {
                        // The session is dead (OTG unplugged, camera slept, or the
                        // bulk pipe wedged) — every transfer returns -1. Close and
                        // re-open so a re-plugged / re-woken camera recovers on its
                        // own, no user tap needed. Back off while it stays gone.
                        _liveView.value = null
                        _status.value = "Reconnecting…"
                        val ok = ptpLock.withLock { openInternal() }
                        if (ok) {
                            // Fresh session — give live view another chance.
                            stateFails = 0; liveFails = 0; liveGaveUp = false
                            _status.value = "Connected"
                        } else { delay(RECONNECT_BACKOFF_MS); continue }
                    }
                }
                delay(if (liveActive) LIVE_INTERVAL_MS else POLL_INTERVAL_MS)
            }
        }
    }

    // ── Control actions (each serialised behind the PTP lock) ────────────────
    private fun act(block: SonyPtpUsb.() -> Unit) {
        if (!_connected.value) return
        scope.launch(io) { ptpLock.withLock { runCatching { ptp.block() } } }
    }

    fun isoUp()       = act { stepIso(true) }
    fun isoDown()     = act { stepIso(false) }
    fun apertureUp()  = act { stepAperture(true) }
    fun apertureDown()= act { stepAperture(false) }
    fun shutterUp()   = act { stepShutter(true) }
    fun shutterDown() = act { stepShutter(false) }
    fun evUp()        = act { stepExposure(true) }
    fun evDown()      = act { stepExposure(false) }
    fun wbUp()        = act { stepWhiteBalance(true) }
    fun wbDown()      = act { stepWhiteBalance(false) }
    fun setWb(value: Int)      = act { setWhiteBalance(value) }
    fun colorTemp(kelvin: Int) = act { setColorTemp(kelvin) }
    fun wbAB(v: Int)  = act { setWbAB(v) }
    fun wbGM(v: Int)  = act { setWbGM(v) }
    fun autofocus()   = act { autofocus(true); Thread.sleep(80); autofocus(false) }
    fun setDro(level: Int) = act { setDro(level) }

    /** Set by the cloud live-upload controller to receive each captured JPEG. */
    var onCaptured: ((String, ByteArray) -> Unit)? = null

    fun snap() {
        if (!_connected.value) return
        scope.launch(io) {
            ptpLock.withLock {
                runCatching {
                    ptp.snap()
                    val cb = onCaptured
                    if (cb != null) {
                        // Wait until the image is actually exposed at 0xFFFFC001
                        // (a 25 MB RAW takes longer than a fixed 400 ms), then pull.
                        ptp.getCapturedJpegWhenReady()?.let { cb("SNAP_${System.currentTimeMillis()}.JPG", it) }
                    }
                }
            }
        }
    }
    // Sends the movie start/stop button. The REC indicator is NOT flipped
    // optimistically here — it is driven by the camera's real recording status in
    // the poll loop (SonyCameraState.isRecording). Until the movie-status prop
    // code is verified from a capture, the camera reports null and REC stays off
    // rather than showing a fake state. TODO(capture): confirm DPC_MOVIE_STATUS.
    fun toggleMovie() { act { movieToggle() } }

    // ── USB device discovery ─────────────────────────────────────────────────
    private fun findPtpCamera(): UsbDevice? {
        val devices = usbManager.deviceList.values
        // Prefer a Sony still-image device; else any still-image (PTP) device.
        return devices.firstOrNull { it.vendorId == SonyPtpUsb_SONY_VID && hasStillImage(it) }
            ?: devices.firstOrNull { hasStillImage(it) }
    }

    private fun hasStillImage(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) if (d.getInterface(i).interfaceClass == 6) return true
        return false
    }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        _status.value = "Waiting for USB permission…"
        return withContext(io) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_USB_PERMISSION) return
                        runCatching { context.unregisterReceiver(this) }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context, 1, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
                )
                usbManager.requestPermission(device, pi)
            }
        }
    }

    private fun hex(v: Int) = "0x%04X".format(v)

    companion object {
        private const val TAG = "SonyRemote"
        // Fallback loop tick when live view is off / gave up: state-only at 4×/s,
        // which leaves the PTP lock free most of the time for snappy control taps
        // (90 ms hammered the lock ~10×/s and made ISO/WB/… laggy/hit-and-miss).
        private const val POLL_INTERVAL_MS = 250L
        // Decoupled cadences when live view is streaming: pull the frame every
        // LIVE tick (~10 fps) but only run the heavier state read every
        // STATE_INTERVAL_MS, so live view stays smooth without the state read
        // (and the reconnect logic it drives) flooding the lock. State liveness
        // still refreshes 4×/s, matching the state-only fallback.
        private const val LIVE_INTERVAL_MS = 100L
        private const val STATE_INTERVAL_MS = 250L
        // Consecutive fully-failed polls before treating the session as dead and
        // attempting a clean reopen (fast when transfers fail instantly, i.e. a
        // dropped OTG). Backoff between reopen attempts while the camera is gone.
        private const val DEAD_AFTER = 5
        private const val RECONNECT_BACKOFF_MS = 1500L
        // Live view = GetObjectInfo+GetObject on 0xFFFFC002. Re-enabled after the
        // transport rewrite (256 KB read buffer + drain/clear-HALT + longer
        // timeout, per argallo/sony-camera-android) which is what made the ~96 KB
        // frame reads reliable. Give-up safety still applies if a body won't stream.
        private const val LIVE_VIEW_ENABLED = true
        private const val LIVE_GIVEUP = 6
        private const val SonyPtpUsb_SONY_VID = 0x054C
        private const val ACTION_USB_PERMISSION =
            "com.RAZStudio.StudioRoom.feature.sony_sync.REMOTE_USB_PERMISSION"
    }
}
