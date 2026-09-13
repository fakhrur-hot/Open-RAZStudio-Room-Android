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
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.EosPropertyLabels
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.EosPropertyDescriptor
import com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonWifiClient
import com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants
import com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic.CanonRemoteShootComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets as ComposeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live Remote Shooting screen.
 *
 * Entry mode: **Remote Control Only**. The PTP session is already
 * live (from Canon Sync), so the user can immediately fire the
 * shutter, half-press AF, drive the lens, and receive captured
 * photos. The viewfinder JPEG stream is OFF by default — saves
 * camera battery / Wi-Fi bandwidth.
 *
 * Top-right **LIVE** pill toggles the viewfinder stream:
 *   - Hollow (outline only) → LV not running. Tap to start.
 *   - Solid red → LV streaming. Tap to stop (returns to remote-
 *     control-only).
 *
 * Lifecycle:
 *   - Fullscreen immersive (status + nav bars hidden, drawing under
 *     any cutout).
 *   - Back is intercepted by [BackHandler] → confirmation dialog.
 *     Confirm tears down LV (if running) AND exits to Canon Sync.
 *   - Connection-loss auto-exits without confirmation.
 */
@Composable
fun CanonRemoteShootContent(component: CanonRemoteShootComponent) {
    val connection by component.connectionState.collectAsState()
    val liveView by component.liveViewState.collectAsState()
    val capturing by component.captureInProgress.collectAsState()
    val captureEvent by component.latestCaptureEvent.collectAsState()
    val afEngaged by component.afEngaged.collectAsState()
    // Default is CARD so the camera does its natural fast write-card-
    // and-fire-0xC181 cycle. The auto-download coordinator listens
    // for the event and pulls the file into the working dir
    // asynchronously — your photos are NEVER lost (they sit on the
    // card as the source of truth) and the camera's RAM is freed
    // immediately for the next shot.
    var captureDest by remember { mutableStateOf(CaptureDest.CARD) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // ── System UI: fullscreen immersive ───────────────────────────────
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ── Auto-exit on session loss ────────────────────────────────────
    LaunchedEffect(connection) {
        if (connection != ConnectionState.Connected) component.onGoBack()
    }

    // ── Make sure post-shutter auto-download is wired ────────────────
    // Without this the CaptureCoordinator (built at connect-time when
    // no folder was armed) never sees the 0xC181 events fired by
    // remote-shutter shots, and pictures sit on the SD card only.
    LaunchedEffect(Unit) { component.ensureCaptureCoordinator() }

    // ── Capture-destination write ──────────────────────────────────
    // Fire the DPC write whenever the user toggles the chip. The 6D
    // sometimes returns DeviceBusy briefly (mid-LV-arm); the repo's
    // call is fire-and-forget so worst case the chip selection
    // doesn't stick the first time and the user re-taps. We no
    // longer gate on the streaming flag because the user might be
    // shooting blind via the optical viewfinder (LV off).
    LaunchedEffect(captureDest) {
        component.setCaptureDestination(captureDest.wireValue)
    }

    // ── Back-confirmation ─────────────────────────────────────────────
    BackHandler { showExitConfirm = true }
    if (showExitConfirm) {
        ExitConfirmDialog(
            liveViewActive = liveView !is CanonSyncRepository.LiveViewState.Idle,
            onConfirm = {
                showExitConfirm = false
                component.stopLiveView()
                component.onGoBack()
            },
            onDismiss = { showExitConfirm = false },
        )
    }

    // ── Property picker sheet state ──────────────────────────────────
    // A single hosted sheet shared by every chip — the chip clicked
    // most recently puts its DPC code here and the sheet renders the
    // matching descriptor.
    var pickerDpc by remember { mutableStateOf<Int?>(null) }
    val pickerScope = rememberCoroutineScope()

    val liveStreaming = liveView is CanonSyncRepository.LiveViewState.Streaming

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Center area changes based on LV state.
        when (val s = liveView) {
            is CanonSyncRepository.LiveViewState.Idle -> RemoteOnlyCenter()
            is CanonSyncRepository.LiveViewState.Starting -> StartingCenter()
            is CanonSyncRepository.LiveViewState.Error -> ErrorCenter(s.message)
            is CanonSyncRepository.LiveViewState.Streaming -> StreamingCenter(s)
        }
        // Top overlay (back / LIVE / capture-dest) + the property
        // chip strips. Both are top-aligned and pinned to the
        // status-bar safe area.
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            TopOverlay(
                captureDest = captureDest,
                onCaptureDestChange = { captureDest = it },
                liveActive = liveStreaming,
                liveBusy = liveView is CanonSyncRepository.LiveViewState.Starting,
                onToggleLive = {
                    when (liveView) {
                        is CanonSyncRepository.LiveViewState.Streaming,
                        is CanonSyncRepository.LiveViewState.Starting -> component.stopLiveView()
                        else -> component.startLiveView()
                    }
                },
                onExit = { showExitConfirm = true },
            )
            PropertyChipStrips(
                component = component,
                enabled = liveStreaming,
                onChipTap = { dpc -> pickerDpc = dpc },
            )
        }
        // Bottom overlay — always present so remote-control buttons
        // are accessible without LV running.
        // ── LV overlays (zoom button, AF overlay, OLC status row) ──
        // Only meaningful while LV is streaming — gated by [liveStreaming].
        if (liveStreaming) {
            LiveViewOverlays(
                component = component,
                pickerScope = pickerScope,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        BottomOverlay(
            capturing = capturing,
            afEngaged = afEngaged,
            captureEvent = captureEvent,
            onShutter = component::triggerShutter,
            onAfOnPress = component::doAf,
            onAfOnRelease = component::cancelAf,
            onDriveLens = component::driveLens,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    pickerDpc?.let { dpc ->
        PropertyPickerSheet(
            dpc = dpc,
            descriptorFlow = component.observeProperty(dpc),
            component = component,
            pickerScope = pickerScope,
            onSelect = { value ->
                pickerScope.launch { component.setProperty(dpc, value) }
                pickerDpc = null
            },
            onDismiss = { pickerDpc = null },
        )
    }
}

// ─────────────────────────── Center variants ────────────────────────

@Composable
private fun RemoteOnlyCenter() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Remote Control",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Shutter, AF, and lens controls only.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Tap LIVE above to also stream the viewfinder.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StartingCenter() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("Starting live view…", color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "The 6D's EVF can take 1–2 seconds to warm up.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ErrorCenter(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                "Live view error",
                color = Color(0xFFFF8A80),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap LIVE above to retry, or use remote-control-only " +
                    "below.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StreamingCenter(state: CanonSyncRepository.LiveViewState.Streaming) {
    var lastBitmap by remember {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(state.latestFrame) {
        val bytes = state.latestFrame
        if (bytes != null && bytes.isNotEmpty()) {
            val decoded = withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (decoded != null) lastBitmap = decoded.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = lastBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Live view",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

// ─────────────────────────── Top overlay ────────────────────────────

@Composable
private fun TopOverlay(
    captureDest: CaptureDest,
    onCaptureDestChange: (CaptureDest) -> Unit,
    liveActive: Boolean,
    liveBusy: Boolean,
    onToggleLive: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Honour the system status-bar / cutout area so the back / LIVE
    // pills don't sit under the clock+battery cluster. The immersive
    // controller hides the bar but the inset still reports its size
    // — using it for top padding keeps the layout stable when the
    // user swipes the bar back into view.
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = topInset + 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.clickable(onClick = onExit),
        ) {
            Text(
                "‹ Back",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        LiveTogglePill(
            active = liveActive,
            busy = liveBusy,
            onClick = onToggleLive,
        )
        Spacer(Modifier.weight(1f))
        CaptureDestChips(current = captureDest, onSelect = onCaptureDestChange)
    }
}

/**
 * Toggle pill for the live-view stream.
 *   - Hollow + grey border, "○ LIVE" label → LV is off. Tap = start.
 *   - Pulsing border, "● LIVE" label → LV is warming up.
 *   - Solid red, white "● LIVE" label → LV is streaming. Tap = stop.
 */
@Composable
private fun LiveTogglePill(
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val (bg, fg, label) = when {
        active -> Triple(Color(0xFFE53935), Color.White, "● LIVE")
        busy -> Triple(Color.Transparent, Color(0xFFE53935), "● LIVE")
        else -> Triple(Color.Transparent, Color.White.copy(alpha = 0.7f), "○ LIVE")
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = if (!active) androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = fg,
        ) else null,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ─────────────────────────── Bottom overlay ─────────────────────────

@Composable
private fun BottomOverlay(
    capturing: Boolean,
    afEngaged: Boolean,
    captureEvent: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent?,
    onShutter: () -> Unit,
    onAfOnPress: () -> Unit,
    onAfOnRelease: () -> Unit,
    onDriveLens: (CanonWifiClient.LensFocusStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DownloadBadge(captureEvent)
        ManualFocusRail(onDriveLens = onDriveLens)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AfOnButton(
                afEngaged = afEngaged,
                onPress = onAfOnPress,
                onRelease = onAfOnRelease,
            )
            ShutterButton(capturing = capturing, onClick = onShutter)
            Spacer(Modifier.size(56.dp))
        }
    }
}

/**
 * Single-line status of the post-shutter auto-download pipeline.
 * Surfaces the [CaptureCoordinator]'s latest event so the user sees
 * shots flowing into the phone in real time. Card is always the
 * source of truth — even if this fails the photo is safe on the SD.
 */
@Composable
private fun DownloadBadge(
    event: com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent?,
) {
    val (label, tint) = when (event) {
        null -> "Auto-download ready" to Color.White.copy(alpha = 0.4f)
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent.Started ->
            "Downloading ${event.filename}…" to Color.White
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent.Progress -> {
            val pct = if (event.expectedSizeBytes > 0)
                (event.bytesWritten * 100 / event.expectedSizeBytes).toInt()
            else 0
            "Downloading ${pct}%…" to Color.White
        }
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent.Completed ->
            "Saved ${event.finalFilename}" to Color(0xFF81C784)
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent.FilteredOut ->
            "Skipped (format filter)" to Color.White.copy(alpha = 0.6f)
        is com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent.Failed ->
            "Download failed — file on card" to Color(0xFFFF8A80)
    }
    Text(
        text = label,
        color = tint,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * Save-destination chip choices, mapped to Canon's CaptureDestination
 * DPC (0xD11C). We deliberately omit the "PC only" variant (wire value
 * 4 = host RAM only) because it implies "save to PC and discard from
 * card", which contradicts the user's expectation in a phone-app
 * context where the phone is doing double duty as both controller
 * AND archive. The two surviving options:
 *
 *   - **Card**: writes to SD card only. No transfer; the user pulls
 *     from the Browse SD Card screen later.
 *   - **Both**: writes to SD card AND streams to the phone's working
 *     directory (the same `saveFolderUri` setting the rest of the
 *     app uses). Wire-equivalent of host-RAM mode but the camera
 *     keeps a card copy as the source of truth.
 */
private enum class CaptureDest(val wireValue: Int, val label: String) {
    CARD(1, "Card"),
    BOTH(3, "Both"),
}

@Composable
private fun CaptureDestChips(
    current: CaptureDest,
    onSelect: (CaptureDest) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (dest in CaptureDest.entries) {
            val selected = current == dest
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) Color.White.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.12f),
                modifier = Modifier.clickable { onSelect(dest) },
            ) {
                Text(
                    dest.label,
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Touch-drag manual-focus slider.
 *
 *  ── Near ──┤━━━━━━━━━━●━━━━━━━━━━├── Far ──
 *
 * Behaviour:
 *  - The thumb is centred at rest. Drag it horizontally to drive the
 *    lens in that direction.
 *  - Magnitude is bucketed by absolute offset from centre:
 *      0–33 % of half-track  →  SMALL step
 *      33–66 %                →  MEDIUM step
 *      66–100 %               →  LARGE step
 *  - While the user is still dragging we re-fire the matching step
 *    every [STEP_INTERVAL_MS] so holding off-centre keeps moving the
 *    lens (Canon's `0x9155 DriveLens` is one-shot per call).
 *  - Releasing snaps the thumb back to centre.
 *
 * The slider is full-width inside its parent column; the thumb width
 * is sized in dp so the maths is layout-independent.
 */
@Composable
private fun ManualFocusRail(
    onDriveLens: (CanonWifiClient.LensFocusStep) -> Unit,
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableStateOf(0f) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Periodic re-fire while held off-centre. `dragging` flips the
    // LaunchedEffect on/off; the body keys on the bucket so a release
    // immediately cancels the next emission.
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (dragging) {
            val step = bucketStep(dragOffsetPx, trackWidthPx)
            if (step != null) onDriveLens(step)
            kotlinx.coroutines.delay(STEP_INTERVAL_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        // Near | Focus | Far labels above the track.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Near", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
            Text("Focus", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Text("Far", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            dragOffsetPx = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffsetPx = 0f
                        },
                    ) { _, drag ->
                        val half = trackWidthPx / 2f
                        dragOffsetPx = (dragOffsetPx + drag.x).coerceIn(-half, half)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Track bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
            )
            // Centre tick.
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 14.dp)
                    .background(Color.White.copy(alpha = 0.4f)),
            )
            // Thumb.
            val thumbColor = when (bucketStep(dragOffsetPx, trackWidthPx)) {
                null -> Color.White
                CanonWifiClient.LensFocusStep.NEAR_SMALL,
                CanonWifiClient.LensFocusStep.FAR_SMALL ->
                    Color(0xFF7FE08A)  // light green
                CanonWifiClient.LensFocusStep.NEAR_MEDIUM,
                CanonWifiClient.LensFocusStep.FAR_MEDIUM ->
                    Color(0xFFFFC857)  // amber
                CanonWifiClient.LensFocusStep.NEAR_LARGE,
                CanonWifiClient.LensFocusStep.FAR_LARGE ->
                    Color(0xFFE53935)  // red — full speed
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                    .size(width = 36.dp, height = 28.dp)
                    .background(thumbColor, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bucketLabel(dragOffsetPx, trackWidthPx),
                    color = Color.Black.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Map the thumb offset to a [CanonWifiClient.LensFocusStep] bucket, or
 * null when within the centre dead-zone (no lens drive issued).
 */
private fun bucketStep(
    offsetPx: Float,
    trackWidthPx: Float,
): CanonWifiClient.LensFocusStep? {
    if (trackWidthPx <= 0f) return null
    val half = trackWidthPx / 2f
    val ratio = (offsetPx / half).coerceIn(-1f, 1f)
    val mag = ratio.absoluteValue
    if (mag < DEAD_ZONE_RATIO) return null
    val toNear = ratio < 0f
    return when {
        mag < SMALL_MAX_RATIO -> if (toNear)
            CanonWifiClient.LensFocusStep.NEAR_SMALL
        else CanonWifiClient.LensFocusStep.FAR_SMALL
        mag < MEDIUM_MAX_RATIO -> if (toNear)
            CanonWifiClient.LensFocusStep.NEAR_MEDIUM
        else CanonWifiClient.LensFocusStep.FAR_MEDIUM
        else -> if (toNear)
            CanonWifiClient.LensFocusStep.NEAR_LARGE
        else CanonWifiClient.LensFocusStep.FAR_LARGE
    }
}

private fun bucketLabel(offsetPx: Float, trackWidthPx: Float): String =
    when (bucketStep(offsetPx, trackWidthPx)) {
        null -> "·"
        CanonWifiClient.LensFocusStep.NEAR_SMALL,
        CanonWifiClient.LensFocusStep.FAR_SMALL -> "S"
        CanonWifiClient.LensFocusStep.NEAR_MEDIUM,
        CanonWifiClient.LensFocusStep.FAR_MEDIUM -> "M"
        CanonWifiClient.LensFocusStep.NEAR_LARGE,
        CanonWifiClient.LensFocusStep.FAR_LARGE -> "L"
    }

/** Re-fire interval while the thumb is held off-centre. */
private const val STEP_INTERVAL_MS: Long = 160L
private const val DEAD_ZONE_RATIO: Float = 0.08f
private const val SMALL_MAX_RATIO: Float = 0.33f
private const val MEDIUM_MAX_RATIO: Float = 0.66f

@Composable
private fun AfOnButton(
    afEngaged: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    // Two states:
    //  - Idle / not engaged  -> hollow red ring + red "AF-On" label.
    //    The red colour signals "AF is OFF right now — your shutter
    //    will fire without focusing".
    //  - Held / engaged      -> filled green disc + white "AF" label.
    //    Camera is actively holding focus-lock via the half-press-
    //    with-AF wire pair; shutter button now captures the locked
    //    focus.
    val bg = if (afEngaged) Color(0xFF4CAF50)
    else Color.Transparent
    val ring = if (afEngaged) Color(0xFF66BB6A) else Color(0xFFE53935)
    val labelColor = if (afEngaged) Color.White else Color(0xFFE53935)
    Surface(
        shape = CircleShape,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(2.dp, ring),
        modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (afEngaged) "AF" else "AF-On",
                color = labelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ShutterButton(capturing: Boolean, onClick: () -> Unit) {
    val ringColor = if (capturing) Color(0xFFE53935) else Color.White
    Box(
        modifier = Modifier
            .size(80.dp)
            .border(width = 4.dp, color = ringColor, shape = CircleShape)
            .clickable(enabled = !capturing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = if (capturing) Color(0xFFE53935).copy(alpha = 0.7f)
                    else Color.White,
                    shape = CircleShape,
                )
                .alpha(if (capturing) 0.6f else 1f),
        )
        if (capturing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ExitConfirmDialog(
    liveViewActive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit remote shooting?") },
        text = {
            Text(
                if (liveViewActive) {
                    "Closes the live-view stream on the camera too. " +
                        "The PTP session stays connected so you can re-enter " +
                        "without re-pairing."
                } else {
                    "Returns to Canon Sync. The PTP session stays connected."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Exit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay") }
        },
    )
}

// ────────────────────────── Property chip strips ─────────────────────

/**
 * Three horizontally-scrollable chip strips stacked under the top bar.
 * Each chip reflects the camera's current value for one DPC; tapping
 * opens the shared [PropertyPickerSheet] for that DPC.
 *
 * `enabled = false` greys every chip and disables the tap handler —
 * used when the LV stream is not running.
 */
@Composable
private fun PropertyChipStrips(
    component: CanonRemoteShootComponent,
    enabled: Boolean,
    onChipTap: (Int) -> Unit,
) {
    val baseAlpha = if (enabled) 1f else 0.35f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(baseAlpha),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Top strip: exposure quad — ISO, Tv, Av, EV. Always horizontally
        // scrollable so a 7-stop EV range or a 60-step ISO list never
        // overflows the line.
        PropertyChipRow(
            chips = listOf(
                PtpIpConstants.DPC_EOS_ISO to "ISO",
                PtpIpConstants.DPC_EOS_TV to "Tv",
                PtpIpConstants.DPC_EOS_AV to "Av",
                PtpIpConstants.DPC_EOS_EXPOSURE_COMP to "EV",
            ),
            component = component,
            enabled = enabled,
            onTap = onChipTap,
        )
        // Middle strip: behaviour — Metering, AF servo mode,
        // AF method (FlexiZone vs Quick), Drive, WB.
        PropertyChipRow(
            chips = listOf(
                PtpIpConstants.DPC_EOS_METERING_MODE to "Mtr",
                PtpIpConstants.DPC_EOS_AF_MODE to "AF",
                PtpIpConstants.DPC_EOS_AF_METHOD to "AF-M",
                PtpIpConstants.DPC_EOS_DRIVE_MODE to "Drv",
                PtpIpConstants.DPC_EOS_WHITE_BALANCE to "WB",
            ),
            component = component,
            enabled = enabled,
            onTap = onChipTap,
        )
        // Bottom strip: format — Image Quality, Aspect, Picture Style.
        PropertyChipRow(
            chips = listOf(
                PtpIpConstants.DPC_EOS_IMAGE_QUALITY to "Qual",
                PtpIpConstants.DPC_EOS_ASPECT_RATIO to "Asp",
                PtpIpConstants.DPC_EOS_PICTURE_STYLE to "Style",
                PtpIpConstants.DPC_EOS_CAMERA_MODE to "Mode",
            ),
            component = component,
            enabled = enabled,
            onTap = onChipTap,
        )
    }
}

@Composable
private fun PropertyChipRow(
    chips: List<Pair<Int, String>>,
    component: CanonRemoteShootComponent,
    enabled: Boolean,
    onTap: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((dpc, label) in chips) {
            PropertyChip(
                dpc = dpc,
                label = label,
                descriptorFlow = component.observeProperty(dpc),
                enabled = enabled,
                onTap = { onTap(dpc) },
            )
        }
    }
}

@Composable
private fun PropertyChip(
    dpc: Int,
    label: String,
    descriptorFlow: Flow<EosPropertyDescriptor?>,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val descriptor by descriptorFlow.collectAsState(initial = null)
    val valueText = descriptor?.let { EosPropertyLabels.chipText(dpc, it.currentValue) } ?: "—"
    val writable = descriptor?.writable == true
    val canTap = enabled && writable
    val bg = if (canTap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = Modifier.clickable(enabled = canTap, onClick = onTap),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 9.sp,
            )
            Text(
                valueText,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ────────────────────────── Property picker sheet ────────────────────

/**
 * Bottom sheet that lists every legal value for the given DPC, marks
 * the current one, and calls [onSelect] when the user taps a row.
 *
 * Renders descriptor-driven — when the descriptor is an enum we list
 * each value; when it's a range we fall back to a minimal text input
 * (slider variants land in PR 3 for WB Kelvin / picture-style sliders).
 *
 * The list reuses [EosPropertyLabels.label] for the row text so wire
 * values become user-readable ("100" / "1/250" / "f/4.0" / "+0 1/3 EV"
 * etc.).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PropertyPickerSheet(
    dpc: Int,
    descriptorFlow: Flow<EosPropertyDescriptor?>,
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Picture-style + WB get richer panels with sub-sliders /
    // sub-axes. Delegate them to specialised composables so the
    // general enum/range path stays clean.
    if (dpc == PtpIpConstants.DPC_EOS_PICTURE_STYLE) {
        PictureStyleSheet(
            descriptorFlow = descriptorFlow,
            component = component,
            pickerScope = pickerScope,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
        return
    }
    if (dpc == PtpIpConstants.DPC_EOS_WHITE_BALANCE) {
        WhiteBalanceSheet(
            descriptorFlow = descriptorFlow,
            component = component,
            pickerScope = pickerScope,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
        return
    }
    val descriptor by descriptorFlow.collectAsState(initial = null)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 480.dp),
        ) {
            Text(
                text = chipTitleFor(dpc),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val d = descriptor
            if (d == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Reading from camera…",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
            } else when (val form = d.form) {
                is EosPropertyDescriptor.Form.Enum -> {
                    LazyColumn {
                        items(form.values) { value ->
                            val selected = value == d.currentValue
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Color(0xFF2E5BFF).copy(alpha = 0.25f)
                                else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable(enabled = d.writable) { onSelect(value) },
                            ) {
                                Text(
                                    text = EosPropertyLabels.label(dpc, value),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                is EosPropertyDescriptor.Form.Range -> {
                    RangeSliderPicker(
                        dpc = dpc,
                        descriptor = d,
                        form = form,
                        onSelect = onSelect,
                    )
                }
                EosPropertyDescriptor.Form.None -> {
                    Text(
                        "No advertised options. Current = " +
                            EosPropertyLabels.label(dpc, d.currentValue),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Range slider for descriptors whose form is `Range(min, max, step)`.
 * Most commonly: WB Kelvin (2500–10000K step 100), picture-style
 * sharpness / contrast / saturation / colour-tone (each -4..+4 or
 * 0..7). Step granularity is honoured — drag snaps to step boundaries.
 *
 * Live label below the slider shows the user-readable value
 * (e.g. "5500 K"). On release we fire [onSelect]; we don't debounce
 * intermediate drag positions because every PTP write blocks on the
 * command channel and the 6D rejects rapid writes with `0xA102`.
 */
@Composable
private fun RangeSliderPicker(
    dpc: Int,
    descriptor: EosPropertyDescriptor,
    form: EosPropertyDescriptor.Form.Range,
    onSelect: (Long) -> Unit,
) {
    var sliderValue by remember(descriptor.currentValue) {
        mutableStateOf(descriptor.currentValue.toFloat())
    }
    val min = form.min.toFloat()
    val max = form.max.toFloat()
    val step = form.step.coerceAtLeast(1L)
    val stepCount = ((max - min) / step.toFloat()).toInt().coerceAtLeast(1) - 1
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = formatRangeValue(dpc, sliderValue.toLong()),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        androidx.compose.material3.Slider(
            value = sliderValue,
            onValueChange = { sliderValue = snapToStep(it, min, step) },
            valueRange = min..max,
            steps = stepCount.coerceAtLeast(0),
            enabled = descriptor.writable,
            onValueChangeFinished = {
                onSelect(sliderValue.toLong())
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatRangeValue(dpc, form.min),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )
            Text(
                "step ${form.step}",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
            )
            Text(
                formatRangeValue(dpc, form.max),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )
        }
    }
}

private fun snapToStep(raw: Float, min: Float, step: Long): Float {
    val s = step.toFloat()
    val stepsFromMin = ((raw - min) / s).toLong()
    return min + stepsFromMin * s
}

private fun formatRangeValue(dpc: Int, value: Long): String = when (dpc) {
    PtpIpConstants.DPC_EOS_COLOR_TEMP -> "$value K"
    PtpIpConstants.DPC_EOS_PS_SHARPNESS,
    PtpIpConstants.DPC_EOS_PS_CONTRAST,
    PtpIpConstants.DPC_EOS_PS_SATURATION,
    PtpIpConstants.DPC_EOS_PS_COLOR_TONE -> {
        // Picture-style adjustments are signed in the wire spec but EOS
        // bodies expose them as 0..7 (sharpness) or -4..+4 (others).
        // Format with a leading sign when negative so the user sees "-2".
        if (value > 0) "+$value" else "$value"
    }
    else -> "$value"
}

/**
 * Picture-style sheet: lists the styles enum at the top, four
 * adjustment sliders (sharpness / contrast / saturation / colour tone)
 * below. Each slider observes its own DPC via the controller and
 * writes through on release — same pattern as the generic
 * [RangeSliderPicker].
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PictureStyleSheet(
    descriptorFlow: Flow<EosPropertyDescriptor?>,
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val descriptor by descriptorFlow.collectAsState(initial = null)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Picture style",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            // Style enum.
            val d = descriptor
            if (d != null && d.form is EosPropertyDescriptor.Form.Enum) {
                val form = d.form
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(form.values) { value ->
                        val selected = value == d.currentValue
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFF2E5BFF).copy(alpha = 0.25f)
                            else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = d.writable) { onSelect(value) },
                        ) {
                            Text(
                                EosPropertyLabels.pictureStyle(value),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Text(
                "Adjustments",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            PsAdjustmentSlider(
                label = "Sharpness",
                dpc = PtpIpConstants.DPC_EOS_PS_SHARPNESS,
                component = component,
                pickerScope = pickerScope,
            )
            PsAdjustmentSlider(
                label = "Contrast",
                dpc = PtpIpConstants.DPC_EOS_PS_CONTRAST,
                component = component,
                pickerScope = pickerScope,
            )
            PsAdjustmentSlider(
                label = "Saturation",
                dpc = PtpIpConstants.DPC_EOS_PS_SATURATION,
                component = component,
                pickerScope = pickerScope,
            )
            PsAdjustmentSlider(
                label = "Colour tone",
                dpc = PtpIpConstants.DPC_EOS_PS_COLOR_TONE,
                component = component,
                pickerScope = pickerScope,
            )
        }
    }
}

@Composable
private fun PsAdjustmentSlider(
    label: String,
    dpc: Int,
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
) {
    val descriptor by component.observeProperty(dpc).collectAsState(initial = null)
    val d = descriptor ?: return
    val form = d.form as? EosPropertyDescriptor.Form.Range ?: return
    var sliderValue by remember(d.currentValue) {
        mutableStateOf(d.currentValue.toFloat())
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
            Text(
                formatRangeValue(dpc, sliderValue.toLong()),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        androidx.compose.material3.Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = snapToStep(it, form.min.toFloat(), form.step.coerceAtLeast(1L))
            },
            valueRange = form.min.toFloat()..form.max.toFloat(),
            steps = (((form.max - form.min) / form.step.coerceAtLeast(1L))
                .toInt() - 1).coerceAtLeast(0),
            enabled = d.writable,
            onValueChangeFinished = {
                pickerScope.launch { component.setProperty(dpc, sliderValue.toLong()) }
            },
        )
    }
}

/**
 * White-balance sheet: WB preset enum at the top, Kelvin slider
 * appears below when "Kelvin" is selected, and a 2-axis A↔B / M↔G
 * shift pad. Kelvin descriptor (0xD01E) is fetched lazily so we don't
 * burn a round-trip on every WB-sheet open.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WhiteBalanceSheet(
    descriptorFlow: Flow<EosPropertyDescriptor?>,
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val descriptor by descriptorFlow.collectAsState(initial = null)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "White balance",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            val d = descriptor
            if (d != null && d.form is EosPropertyDescriptor.Form.Enum) {
                val form = d.form
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(form.values) { value ->
                        val selected = value == d.currentValue
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFF2E5BFF).copy(alpha = 0.25f)
                            else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = d.writable) { onSelect(value) },
                        ) {
                            Text(
                                EosPropertyLabels.whiteBalance(value),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            // Kelvin slider — only relevant when "Kelvin" is the
            // active WB. The slider still shows for visibility; the
            // write happens regardless and the camera will use it
            // once the user picks the Kelvin preset above.
            val kelvinDescriptor by component.observeProperty(PtpIpConstants.DPC_EOS_COLOR_TEMP)
                .collectAsState(initial = null)
            val k = kelvinDescriptor
            if (k != null && k.form is EosPropertyDescriptor.Form.Range) {
                Text(
                    "Kelvin",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                RangeSliderPicker(
                    dpc = PtpIpConstants.DPC_EOS_COLOR_TEMP,
                    descriptor = k,
                    form = k.form,
                    onSelect = { value ->
                        pickerScope.launch {
                            component.setProperty(PtpIpConstants.DPC_EOS_COLOR_TEMP, value)
                        }
                    },
                )
            }
        }
    }
}

/** Friendly title for the sheet header, keyed by DPC. */
private fun chipTitleFor(dpc: Int): String = when (dpc) {
    PtpIpConstants.DPC_EOS_ISO -> "ISO"
    PtpIpConstants.DPC_EOS_TV -> "Shutter speed"
    PtpIpConstants.DPC_EOS_AV -> "Aperture"
    PtpIpConstants.DPC_EOS_EXPOSURE_COMP -> "Exposure compensation"
    PtpIpConstants.DPC_EOS_METERING_MODE -> "Metering"
    PtpIpConstants.DPC_EOS_AF_MODE -> "AF mode"
    PtpIpConstants.DPC_EOS_DRIVE_MODE -> "Drive mode"
    PtpIpConstants.DPC_EOS_WHITE_BALANCE -> "White balance"
    PtpIpConstants.DPC_EOS_IMAGE_QUALITY -> "Image quality"
    PtpIpConstants.DPC_EOS_ASPECT_RATIO -> "Aspect ratio"
    PtpIpConstants.DPC_EOS_PICTURE_STYLE -> "Picture style"
    PtpIpConstants.DPC_EOS_CAMERA_MODE -> "Camera mode"
    else -> "0x${dpc.toString(16).uppercase()}"
}

// ───────────────────────── Live-view overlays ────────────────────────

/**
 * The collection of overlays that ride on top of the LV image:
 *
 *  - **Zoom button** in the top-right corner of the LV preview area —
 *    cycles `DPC_EOS_EVF_ZOOM` through 1× → 5× → 10× → 1× on each tap.
 *  - **AF point grid** centred on the preview — 11 fixed points when
 *    the AfMethod is Quick AF (matches the body's phase-detect array);
 *    a single follow-the-tap reticle when AfMethod is FlexiZone.
 *  - **Status row** along the bottom of the preview area matching the
 *    layout of the reference image — AE✱ / Flash / ExpComp / ISO / Tv-Av
 *    / ExpScale / WB / Shots-remaining.
 *
 * Only mounted while LV is streaming.
 */
@Composable
private fun LiveViewOverlays(
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    // CRITICAL: do NOT fillMaxSize() here — that would put a transparent
    // pointer-event sink over the entire screen, swallowing clicks on
    // the TopOverlay (Back / LIVE / chip strips) and BottomOverlay
    // (shutter / AF-On / MF slider) underneath. The overlay box must
    // match the LV image rectangle exactly so taps OUTSIDE the image
    // fall through to whichever overlay actually wants them.
    //
    // The LV image in [StreamingCenter] is drawn as
    // `fillMaxWidth().aspectRatio(3f/2f)` centred. We replicate that
    // geometry here so the AF reticle lives in the same coordinate
    // space the user sees.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f),
    ) {
        // AF overlay fills the LV rectangle — tap routing happens
        // through its FlexiZone reticle when AfMethod is FlexiZoneAF.
        AfPointOverlay(
            component = component,
            pickerScope = pickerScope,
            modifier = Modifier.fillMaxSize(),
        )
        // Zoom button — top-right corner of the LV rectangle.
        ZoomCycleButton(
            component = component,
            pickerScope = pickerScope,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp, top = 8.dp),
        )
        // Status row pinned to the bottom edge of the LV rectangle
        // (NOT to screen-bottom — that would collide with the shutter
        // button.)
        OlcStatusRow(
            component = component,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

/**
 * Cycles `DPC_EOS_EVF_ZOOM` through `1 → 5 → 10 → 1`. Each tap writes
 * the next value via the property controller; the camera's echo event
 * lands ~200 ms later and refreshes the chip text.
 *
 * Disabled when the descriptor advertises the property as read-only,
 * which the 6D occasionally does mid-AF-cycle.
 */
@Composable
private fun ZoomCycleButton(
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val descriptor by component.observeProperty(PtpIpConstants.DPC_EOS_EVF_ZOOM)
        .collectAsState(initial = null)
    val current = descriptor?.currentValue ?: 1L
    val next: Long = when (current) {
        1L -> 5L
        5L -> 10L
        10L -> 1L
        else -> 1L
    }
    val writable = descriptor?.writable == true
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = modifier.clickable(enabled = writable) {
            pickerScope.launch {
                component.setProperty(PtpIpConstants.DPC_EOS_EVF_ZOOM, next)
            }
        },
    ) {
        Text(
            text = "${EosPropertyLabels.evfZoom(current)} ›",
            color = if (writable) Color.White else Color.White.copy(alpha = 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Eleven fixed AF points laid out as the 6D's actual viewfinder
 * cluster (diamond core plus two side wings). Tap a point to make it
 * the selected AF point — we write `DPC_EOS_AF_POINT_SET` and then
 * trigger `0x9154 DoAf` so the camera locks immediately.
 *
 * When AfMethod is FlexiZone or LiveZone, the fixed grid disappears
 * and the overlay becomes a single tap-anywhere reticle that snaps
 * to wherever the user pressed.
 */
@Composable
private fun AfPointOverlay(
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val afMethodDescriptor by component.observeProperty(PtpIpConstants.DPC_EOS_AF_METHOD)
        .collectAsState(initial = null)
    val method = afMethodDescriptor?.currentValue ?: 0L
    val isQuickAf = method == 0x02L                            // Quick AF
    val isFlexiZone = method == 0x00L || method == 0x04L        // FlexiZone / LiveZone
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when {
            isQuickAf -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawElevenPointGrid()
                }
            }
            isFlexiZone -> {
                FlexiZoneReticle(
                    component = component,
                    pickerScope = pickerScope,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                // Other AF methods (Face+Tracking, Live 1-pt) — leave
                // the LV unobstructed; the user can still tap the AF
                // chip to switch to FlexiZone for tap-to-focus.
            }
        }
        // Method indicator pill sits in the top-left of the LV area.
        AfMethodBadge(
            method = method,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 4.dp),
        )
    }
}

@Composable
private fun AfMethodBadge(method: Long, modifier: Modifier = Modifier) {
    val label = when (method) {
        0x00L -> "FlexiZone — tap to focus"
        0x01L -> "Face+Tracking"
        0x02L -> "Quick AF — 11-pt"
        0x03L -> "Live 1-pt"
        0x04L -> "LiveZone — tap to focus"
        else -> "AF mode 0x${method.toString(16).uppercase()}"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Render the 11-point cluster matching the EOS 6D's viewfinder. Points
 * are normalised to the canvas; the centre point is highlighted in
 * blue (cross-type), the rest are outlined.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElevenPointGrid() {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    val pts = afPointPositions(w, h)
    val side = (minOf(w, h) * 0.06f).coerceAtLeast(14f)
    for ((i, p) in pts.withIndex()) {
        val isCentre = i == 5  // centre is index 5 in our layout
        drawRect(
            color = if (isCentre) Color(0xFF2196F3) else Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(p.x - side / 2, p.y - side / 2),
            size = androidx.compose.ui.geometry.Size(side, side),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
    }
    // Centre cross-hair so the user can see where dead-centre is.
    drawCircle(
        color = Color(0xFF2196F3),
        radius = 3f,
        center = androidx.compose.ui.geometry.Offset(cx, cy),
    )
}

private data class AfPoint(val x: Float, val y: Float)

/**
 * Eleven-point cluster, normalised to the LV preview canvas. Numbers
 * derived from the 6D's actual coverage area (centre ±~25 % vertical,
 * ±~30 % horizontal, with two wing points at ±~40 % horizontal).
 */
private fun afPointPositions(w: Float, h: Float): List<AfPoint> {
    val cx = w * 0.5f
    val cy = h * 0.5f
    val dx = w * 0.12f
    val dy = h * 0.18f
    return listOf(
        AfPoint(cx, cy - dy),                  // 0  top
        AfPoint(cx - dx, cy - dy * 0.5f),      // 1  upper-left
        AfPoint(cx + dx, cy - dy * 0.5f),      // 2  upper-right
        AfPoint(cx - dx, cy + dy * 0.5f),      // 3  lower-left
        AfPoint(cx + dx, cy + dy * 0.5f),      // 4  lower-right
        AfPoint(cx, cy),                       // 5  centre (cross-type)
        AfPoint(cx - dx * 1.8f, cy),           // 6  far-left wing
        AfPoint(cx + dx * 1.8f, cy),           // 7  far-right wing
        AfPoint(cx, cy + dy),                  // 8  bottom
        AfPoint(cx - dx, cy + dy * 0.0f),      // 9  middle-left
        AfPoint(cx + dx, cy + dy * 0.0f),      // 10 middle-right
    )
}

/**
 * Tap-to-focus reticle for FlexiZone / LiveZone AF modes.
 *
 * Tapping anywhere on the LV canvas:
 *  1. Computes the normalised position (0..1 fraction of canvas
 *     width/height).
 *  2. Calls [CanonRemoteShootComponent.setLiveAfPoint] which writes
 *     `PROP_LV_AFFRAME (0x80050007)` via opcode `0x915A
 *     OC_EOS_SET_LV_AF_FRAME_PROP` with the camera's native sensor
 *     pixel coords (Magic Lantern's `move_lv_afframe` path), then
 *     fires `DoAf` so the camera locks at the new point — mirroring
 *     EOS Utility's click-to-focus behaviour.
 *  3. Draws an animated reticle at the tap position so the user has
 *     immediate visual feedback while the LV stream catches up
 *     (~1-2 frames later the green AF box renders on the LV itself).
 *
 * Only mounted by [AfPointOverlay] when AfMethod is FlexiZoneAF
 * (`0x00`) or LiveZone (`0x04`). Quick AF uses the fixed grid path
 * instead.
 */
@Composable
private fun FlexiZoneReticle(
    component: CanonRemoteShootComponent,
    pickerScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var tap by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var canvasSize by remember {
        mutableStateOf(androidx.compose.ui.geometry.Size.Zero)
    }
    Box(
        modifier = modifier
            .onSizeChanged { size ->
                canvasSize = androidx.compose.ui.geometry.Size(
                    size.width.toFloat(),
                    size.height.toFloat(),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    tap = offset
                    val w = canvasSize.width
                    val h = canvasSize.height
                    if (w > 0f && h > 0f) {
                        val nx = (offset.x / w).coerceIn(0f, 1f)
                        val ny = (offset.y / h).coerceIn(0f, 1f)
                        android.util.Log.i(
                            "CanonRemoteShoot",
                            "tap@(%.1f,%.1f) box=%.0fx%.0f -> nx=%.3f ny=%.3f".format(
                                offset.x, offset.y, w, h, nx, ny,
                            ),
                        )
                        component.setLiveAfPoint(nx, ny)
                    }
                }
            },
    ) {
        val pos = tap
        if (pos != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Outer focus square (the AF rectangle, green tone like
                // the camera's own indicator).
                val side = 56f
                drawRect(
                    color = Color(0xFF66BB6A),
                    topLeft = androidx.compose.ui.geometry.Offset(
                        pos.x - side / 2,
                        pos.y - side / 2,
                    ),
                    size = androidx.compose.ui.geometry.Size(side, side),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )
                // Crosshair tick for precise centre.
                drawCircle(
                    color = Color(0xFF66BB6A),
                    radius = 3f,
                    center = pos,
                )
            }
        }
    }
}

/**
 * Bottom status row mirroring the layout from the reference image:
 *
 *   ✱  ⚡  ⓘ ±0.0  ISO 0000   1/0    f/0   [−2 ━━━━━●━━━━━ +2]   WB  ●0
 *
 * Every cell observes its own DPC and renders best-effort. OLCInfo
 * (camera-pushed status events like AE-lock, focus confirmation,
 * flash-ready) is decoded by [OlcStatusDecoder] in a later PR; for
 * now those cells render with a dim placeholder.
 */
@Composable
private fun OlcStatusRow(
    component: CanonRemoteShootComponent,
    modifier: Modifier = Modifier,
) {
    val iso by component.observeProperty(PtpIpConstants.DPC_EOS_ISO)
        .collectAsState(initial = null)
    val tv by component.observeProperty(PtpIpConstants.DPC_EOS_TV)
        .collectAsState(initial = null)
    val av by component.observeProperty(PtpIpConstants.DPC_EOS_AV)
        .collectAsState(initial = null)
    val ev by component.observeProperty(PtpIpConstants.DPC_EOS_EXPOSURE_COMP)
        .collectAsState(initial = null)
    val wb by component.observeProperty(PtpIpConstants.DPC_EOS_WHITE_BALANCE)
        .collectAsState(initial = null)
    val shots by component.observeProperty(PtpIpConstants.DPC_EOS_AVAILABLE_SHOTS)
        .collectAsState(initial = null)
    val olc by component.olcInfo.collectAsState(initial = null)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusCell("✱", soft = olc?.aeLocked != true)
        StatusCell("⚡", soft = olc?.flashReady != true)
        StatusCell(
            ev?.let { EosPropertyLabels.exposureComp(it.currentValue) } ?: "±0.0 EV",
            soft = ev == null,
        )
        Spacer(Modifier.weight(1f))
        StatusCell(
            iso?.let { "ISO ${EosPropertyLabels.iso(it.currentValue)}" } ?: "ISO —",
            soft = iso == null,
        )
        StatusCell(
            tv?.let { EosPropertyLabels.shutterSpeed(it.currentValue) } ?: "—",
            soft = tv == null,
        )
        StatusCell(
            av?.let { EosPropertyLabels.aperture(it.currentValue) } ?: "—",
            soft = av == null,
        )
        Spacer(Modifier.weight(1f))
        StatusCell(
            wb?.let { "WB " + EosPropertyLabels.whiteBalance(it.currentValue) } ?: "WB",
            soft = wb == null,
        )
        StatusCell(
            shots?.let { "●${it.currentValue}" } ?: "●—",
            soft = shots == null,
        )
    }
}

@Composable
private fun StatusCell(text: String, soft: Boolean = false) {
    Text(
        text = text,
        color = if (soft) Color.White.copy(alpha = 0.35f) else Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}
