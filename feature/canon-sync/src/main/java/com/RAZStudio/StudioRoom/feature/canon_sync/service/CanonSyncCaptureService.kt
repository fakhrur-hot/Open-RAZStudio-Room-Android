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

package com.RAZStudio.StudioRoom.feature.canon_sync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Durability layer for Canon Sync. Keeps the capture pipeline alive while the
 * user has a folder armed AND the camera session is connected, so backgrounding
 * the app, screen-off, or a transient stop doesn't tear down an in-flight
 * shoot.
 *
 * Defenses, layered per claude advanced plan.md §3.7:
 *
 *  1. **`dataSync` foreground service.** Persistent non-dismissible notification
 *     keeps the process at FOREGROUND oom_adj — Android's documented contract
 *     for "user-initiated network sync" work. Required on Android 14+ to
 *     declare `FOREGROUND_SERVICE_DATA_SYNC` permission.
 *
 *  2. **`WifiLock` at `WIFI_MODE_FULL_HIGH_PERF`.** Prevents the Wi-Fi radio
 *     from entering power-save mode for the whole armed session. Without this,
 *     5 GHz throughput collapses mid-burst and the OS may silently dissociate
 *     from the camera AP. Acquired on service start; released on stop.
 *
 *  3. **Transient `PARTIAL_WAKE_LOCK`.** Acquired only between
 *     [CaptureEvent.Started] and the matching terminal event. Released
 *     immediately afterwards. Long-held wake locks are a battery-policy red
 *     flag and the FGS alone holds the process at FOREGROUND oom_adj during
 *     idle, so the wake lock only matters during the actual TCP transfer when
 *     the screen could legitimately go off.
 *
 * The service does NOT own the connection — [CanonSyncRepository] does. The
 * service merely observes the repository's state flows and adjusts its own
 * layered defenses + notification text. Multiple start/stop calls are
 * idempotent.
 */
@AndroidEntryPoint
internal class CanonSyncCaptureService : Service() {

    @Inject lateinit var repository: CanonSyncRepository
    @Inject lateinit var durationTracker: SessionDurationTracker

    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private var budgetMonitorJob: Job? = null
    private var hasWarnedThisSession: Boolean = false

    private val notificationManager: NotificationManager by lazy {
        getSystemService<NotificationManager>()!!
    }
    private val wifiManager: WifiManager? by lazy { getSystemService<WifiManager>() }
    private val powerManager: PowerManager? by lazy { getSystemService<PowerManager>() }

    private var wifiLock: WifiManager.WifiLock? = null
    private var transferWakeLock: PowerManager.WakeLock? = null

    private var currentTitle: String = ""
    private var currentText: String = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireWifiLock()
        durationTracker.noteSessionStart()
        observeRepository()
        startBudgetMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfWithCleanup()
                return START_NOT_STICKY
            }
            else -> startForegroundCompat()
        }
        // START_REDELIVER_INTENT: if the OS kills us and restarts, we re-enter
        // with the same Intent. The observer then reconciles whether the
        // session is still armed/connected and either resumes or self-stops.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        budgetMonitorJob?.cancel()
        budgetMonitorJob = null
        durationTracker.noteSessionStop()
        releaseTransferWakeLock()
        releaseWifiLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15 / API 35+ safety net. When the system decides our `dataSync`
     * FGS has used its 6-hour cumulative budget, this callback fires. We have
     * "a few seconds" to stop — otherwise the OS throws
     * `ForegroundServiceDidNotStopInTimeException` (ANR-class crash). In
     * practice we should already have soft-stopped via [startBudgetMonitor]
     * well before this fires, so this is the belt-and-braces guarantee.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Tag the duration as "exhausted" so the UI reflects it next time the
        // user opens the screen.
        stopSelfWithCleanup()
    }

    override fun onBind(intent: Intent?) = null

    // ---------- dataSync budget monitor (Android 15+ behavior) ----------

    /**
     * Polls [SessionDurationTracker] at a low cadence and reacts as the
     * dataSync FGS budget drains:
     *
     *   - At ≥5h cumulative use: post a one-time warning notification with a
     *     "Keep running" action that opens the app, which is the only
     *     documented way to refill the budget.
     *   - At ≥5h50m cumulative use: graceful stop. We do this proactively
     *     instead of waiting for the OS's `onTimeout` callback, which only
     *     gives "a few seconds" before throwing
     *     `ForegroundServiceDidNotStopInTimeException`.
     *
     * Cadence is 30 s — fine grained enough that we react well before either
     * threshold even after a wake from doze, cheap enough that it doesn't
     * affect battery.
     */
    private fun startBudgetMonitor() {
        budgetMonitorJob?.cancel()
        budgetMonitorJob = serviceScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val total = durationTracker.snapshotNow().totalMillisAt(now)
                if (total >= SessionDurationTracker.SOFT_STOP_THRESHOLD_MILLIS) {
                    softStopForBudgetExhaustion()
                    return@launch
                }
                if (total >= SessionDurationTracker.WARN_THRESHOLD_MILLIS &&
                    !hasWarnedThisSession
                ) {
                    hasWarnedThisSession = true
                    surfaceBudgetWarning()
                }
                delay(BUDGET_POLL_INTERVAL_MS)
            }
        }
    }

    private fun softStopForBudgetExhaustion() {
        // Replace the notification with a final "stopped" message before
        // tearing down so the user understands why capture ended.
        updateNotification(
            title = "Canon Sync paused",
            text = "Background limit reached. Open the app to resume.",
        )
        stopSelfWithCleanup()
    }

    private fun surfaceBudgetWarning() {
        // Update the existing notification with an explicit warning. The
        // notification already opens the app on tap (via the default content
        // intent), which is what refills the budget — no separate action
        // button needed.
        updateNotification(
            title = "Canon Sync — about an hour left",
            text = "Open the app to keep capturing past the 6 h background limit.",
        )
    }

    // ---------- Repository observation ----------

    private fun observeRepository() {
        observerJob?.cancel()
        observerJob = combine(
            repository.connectionState,
            repository.armedFolderUri,
            repository.latestCaptureEvent,
            repository.batchState,
        ) { connection, armed, capture, batch ->
            ObservedState(connection, armed != null, capture, batch)
        }
            .distinctUntilChanged()
            .onEach(::reconcile)
            .launchIn(serviceScope)
    }

    private fun reconcile(state: ObservedState) {
        // Self-stop when the camera session ends. The activity doesn't have
        // to remember to call stopService(); the service unwinds itself
        // based on observed truth. We no longer gate on `armed` because the
        // WifiLock + heartbeat must run from the moment we connect — those
        // defeat Wi-Fi power save and the 6D's NIC sleep timer, both of
        // which kill the AP within seconds even if no folder is armed yet.
        if (state.connection != ConnectionState.Connected) {
            stopSelfWithCleanup()
            return
        }

        // Batch downloader takes priority over single-shot capture
        // events for the notification — when the user is bulk-pulling
        // the card they care about "N of M photos left", not the
        // single-shot Started/Progress/Completed cycle.
        val batch = state.batch
        if (batch != null && batch !is CanonSyncRepository.CanonBatchPhase.Idle) {
            updateNotificationFromBatch(batch)
            return
        }

        when (val ev = state.lastCaptureEvent) {
            is CaptureEvent.Started -> {
                acquireTransferWakeLock()
                updateNotification(
                    title = "Capturing $sessionLabel",
                    text = "${ev.filename} — ${humanReadable(ev.expectedSizeBytes)}",
                )
            }
            is CaptureEvent.Progress -> {
                val pct = if (ev.expectedSizeBytes > 0) {
                    (ev.bytesWritten * 100 / ev.expectedSizeBytes).toInt().coerceIn(0, 100)
                } else 0
                updateNotification(
                    title = "Capturing $sessionLabel",
                    text = "$pct% — ${humanReadable(ev.bytesWritten)}/${humanReadable(ev.expectedSizeBytes)}",
                )
            }
            is CaptureEvent.Completed -> {
                releaseTransferWakeLock()
                updateNotification(
                    title = "Canon Sync — $sessionLabel",
                    text = "Last: ${ev.finalFilename}",
                )
            }
            is CaptureEvent.Failed -> {
                releaseTransferWakeLock()
                updateNotification(
                    title = "Canon Sync — $sessionLabel",
                    text = "Failed: ${ev.filename}",
                )
            }
            is CaptureEvent.FilteredOut, null -> {
                updateNotification(
                    title = "Canon Sync — $sessionLabel",
                    text = "Waiting for next shot",
                )
            }
        }
    }

    private val sessionLabel: String
        get() = "armed"

    // ---------- Foreground service notification ----------

    private fun startForegroundCompat() {
        // Combine CONNECTED_DEVICE (semantic match for camera-AP work) +
        // DATA_SYNC (proven on Android 14+, declared in core:data manifest).
        // Per Google's FGS docs, multiple types are OR'd and the system
        // applies the union of the permitted lifetimes — so we get both
        // dataSync's 6-hour budget AND connectedDevice's "active hardware
        // I/O" policy, whichever the OEM/OS prefers to enforce. OEM
        // battery savers (MIUI / Honor / Oppo) sometimes whitelist
        // connectedDevice while restricting dataSync; this covers both.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }
    }

    /**
     * Translate a [CanonSyncRepository.CanonBatchPhase] into a single live
     * notification line. Drives the "N photos left" indicator the user
     * sees in the status tray while the Batch Download screen is open.
     */
    private fun updateNotificationFromBatch(batch: CanonSyncRepository.CanonBatchPhase) {
        when (batch) {
            is CanonSyncRepository.CanonBatchPhase.Idle -> Unit
            is CanonSyncRepository.CanonBatchPhase.Scanning -> updateNotification(
                title = "Batch download",
                text = "Scanning camera card…",
            )
            is CanonSyncRepository.CanonBatchPhase.Running -> {
                val left = (batch.totalCount - batch.doneCount - batch.skippedCount)
                    .coerceAtLeast(0)
                updateNotification(
                    title = "Batch download — $left left",
                    text = "${batch.currentFilename} " +
                        "(${humanReadable(batch.currentBytes)}/" +
                        "${humanReadable(batch.currentBytesTotal)})",
                )
            }
            is CanonSyncRepository.CanonBatchPhase.Watching -> updateNotification(
                title = "Batch download — watching",
                text = "${batch.doneCount} downloaded · waiting for new shots",
            )
            is CanonSyncRepository.CanonBatchPhase.Error -> updateNotification(
                title = "Batch download — error",
                text = batch.message,
            )
        }
    }

    private var lastNotifyMs: Long = 0L
    private fun updateNotification(title: String, text: String) {
        if (currentTitle == title && currentText == text) return
        currentTitle = title
        currentText = text
        // Cap notification updates at ~1 Hz. Android's NotificationManager
        // sheds packages exceeding ~5 Hz ("Package enqueue rate ... Shedding")
        // which dropped legitimate updates during fast download progress
        // ticks. Skipping mid-stream updates is harmless — the next state
        // change (file done / new file starting) will go through.
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < 1_000L) return
        lastNotifyMs = now
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val title = currentTitle.ifEmpty { "Canon Sync" }
        val text = currentText.ifEmpty { "Waiting for camera shots" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Canon Sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Active while Canon Sync is capturing shots from the camera"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ---------- WifiLock ----------

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF / WIFI_MODE_FULL are
    // marked deprecated in API 29+ "Manage Wi-Fi power state via the system"
    // but remain the documented mechanism for keeping the radio out of
    // power-save during bulk transfer (claude advanced plan.md §3.7 Layer 2).
    // There is no replacement API for our use case.
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        } else {
            WifiManager.WIFI_MODE_FULL
        }
        wifiLock = wifiManager?.createWifiLock(mode, WIFI_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.runCatching { if (isHeld) release() }
        wifiLock = null
    }

    // ---------- Transient wake lock per transfer ----------

    private fun acquireTransferWakeLock() {
        if (transferWakeLock?.isHeld == true) return
        transferWakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        )?.apply {
            setReferenceCounted(false)
            // 2-minute safety timeout — never hold a wake lock longer than a
            // single object transfer should take, even on a slow body / huge
            // CR3. Releases automatically if we forget to.
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseTransferWakeLock() {
        transferWakeLock?.runCatching { if (isHeld) release() }
        transferWakeLock = null
    }

    // ---------- Shutdown ----------

    private fun stopSelfWithCleanup() {
        observerJob?.cancel()
        observerJob = null
        releaseTransferWakeLock()
        releaseWifiLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private data class ObservedState(
        val connection: ConnectionState,
        val armed: Boolean,
        val lastCaptureEvent: CaptureEvent?,
        val batch: CanonSyncRepository.CanonBatchPhase? = null,
    )

    companion object {
        private const val CHANNEL_ID = "canon_sync_capture"
        private const val NOTIFICATION_ID = 4271
        private const val WIFI_LOCK_TAG = "CanonSync:WifiLock"
        private const val WAKE_LOCK_TAG = "CanonSync:TransferWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 1000L
        private const val BUDGET_POLL_INTERVAL_MS = 30L * 1000L

        const val ACTION_STOP = "com.RAZStudio.StudioRoom.canon_sync.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, CanonSyncCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CanonSyncCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

private fun humanReadable(bytes: Long): String = when {
    bytes <= 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}
