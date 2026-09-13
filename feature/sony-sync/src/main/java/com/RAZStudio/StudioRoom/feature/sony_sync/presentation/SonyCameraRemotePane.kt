/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

package com.RAZStudio.StudioRoom.feature.sony_sync.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.feature.sony_sync.presentation.screenLogic.SonyCameraRemoteController

/**
 * USB Camera Remote page — live view + full control, mirroring the Imaging Edge
 * Remote layout. Responsive: **landscape** puts the control column beside the
 * live view (like the PC app); **portrait** stacks live view on top, controls
 * below.
 */
@Composable
fun SonyCameraRemotePane(
    controller: SonyCameraRemoteController,
    onExit: () -> Unit,
) {
    val live by controller.liveView.collectAsState()
    val state by controller.state.collectAsState()
    val status by controller.status.collectAsState()
    val connected by controller.connected.collectAsState()
    val recording by controller.recording.collectAsState()

    val liveView = @Composable { modifier: Modifier ->
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = live
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Live view",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    if (!connected) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    if (connected) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Live view isn't available for this camera over USB — " +
                                "settings and controls below stay live.",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            // Top-left back + REC badge overlay.
            Row(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                if (recording) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    val controls = @Composable { modifier: Modifier ->
        ControlsPanel(controller, state, recording, modifier)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                liveView(Modifier.weight(1f).fillMaxHeight())
                controls(
                    Modifier.width(340.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                liveView(Modifier.fillMaxWidth().weight(1f))
                controls(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlsPanel(
    c: SonyCameraRemoteController,
    state: com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyCameraState,
    recording: Boolean,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Shooting row ────────────────────────────────────────────────
        SectionLabel("Shooting")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("AF", onClick = c::autofocus)
            RoundAction("◉", onClick = c::snap, big = true)
            RoundAction(if (recording) "■" else "●", tint = Color.Red, onClick = c::toggleMovie)
        }

        // ── Main settings (stepper controls) ────────────────────────────
        // Mode-aware gating (exposure program 0x500E): A can't set shutter, S
        // can't set aperture, P sets neither, M sets both. Locked controls render
        // read-only (no arrows, greyed) instead of doing nothing.
        val pgm = state.exposureProgram
        val apertureEnabled = pgm != 2L && pgm != 4L   // not P, not S
        val shutterEnabled  = pgm != 2L && pgm != 3L   // not P, not A
        SectionLabel("Main Settings")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("Mode", state.modeLabel, onUp = null, onDown = null, modifier = Modifier.weight(1f))
            Stepper("Shutter", state.shutterLabel,
                onUp = if (shutterEnabled) c::shutterUp else null,
                onDown = if (shutterEnabled) c::shutterDown else null,
                modifier = Modifier.weight(1f), enabled = shutterEnabled)
            Stepper("F", state.fLabel,
                onUp = if (apertureEnabled) c::apertureUp else null,
                onDown = if (apertureEnabled) c::apertureDown else null,
                modifier = Modifier.weight(1f), enabled = apertureEnabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("ISO", state.isoLabel, c::isoUp, c::isoDown, Modifier.weight(1f))
            Stepper("EV", state.evLabel, c::evUp, c::evDown, Modifier.weight(1f))
            Stepper("DRO", state.droLabel, onUp = null, onDown = null, modifier = Modifier.weight(1f))
        }
        // WB — up/down cycles presets; long-press opens the full WB dialog
        // (preset grid + colour temp + A-B / G-M fine tune), IEM-style.
        var showWbDialog by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("WB", state.wbLabel, c::wbUp, c::wbDown,
                onLongPress = { showWbDialog = true }, modifier = Modifier.weight(1f))
            // Focus mode — read-only (standard PTP FocusMode 0x500A). A stepper
            // control needs the write path verified from a capture first.
            Stepper("Focus", state.focusModeLabel, onUp = null, onDown = null, modifier = Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        if (showWbDialog) WbDialog(c, state) { showWbDialog = false }

        // ── Sub settings — DRO level chips ──────────────────────────────
        SectionLabel("DRO")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { lvl ->
                OutlinedButton(onClick = { c.setDro(lvl) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Lv$lvl", fontSize = 12.sp)
                }
            }
        }
    }
}

/** WB presets in the A7 II 0x5005 enum order (matches Imaging Edge's grid). */
private val WB_PRESETS = listOf(
    0x0002 to "AWB", 0x0004 to "Daylight", 0x0011 to "Shade", 0x8010 to "Cloudy",
    0x8006 to "Incand.", 0x0001 to "Fluor-1", 0x8002 to "Fluor0", 0x8003 to "Fluor+1",
    0x8004 to "Fluor+2", 0x8007 to "Flash", 0x0030 to "U/W", 0x8012 to "Temp",
    0x8020 to "Custom1", 0x8021 to "Custom2", 0x8022 to "Custom3",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WbDialog(
    c: SonyCameraRemoteController,
    state: com.RAZStudio.StudioRoom.feature.sony_sync.data.SonyCameraState,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("White Balance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.wbLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                // Preset grid.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WB_PRESETS.forEach { (value, label) ->
                        val selected = state.whiteBalance?.toInt() == value
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = { c.setWb(value) },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                // Colour temperature.
                WbAdjustRow("Color Temp.", state.colorTemp?.let { "${it}K" } ?: "—",
                    onDown = { c.colorTemp(((state.colorTemp ?: 5500L).toInt() - 100).coerceIn(2500, 9900)) },
                    onUp   = { c.colorTemp(((state.colorTemp ?: 5500L).toInt() + 100).coerceIn(2500, 9900)) })
                // A-B (amber/blue) and G-M (green/magenta) fine tune.
                WbAdjustRow("A-B", state.wbAB?.toString() ?: "0",
                    onDown = { c.wbAB(((state.wbAB ?: 0L).toInt() - 1).coerceIn(-7, 7)) },
                    onUp   = { c.wbAB(((state.wbAB ?: 0L).toInt() + 1).coerceIn(-7, 7)) })
                WbAdjustRow("G-M", state.wbGM?.toString() ?: "0",
                    onDown = { c.wbGM(((state.wbGM ?: 0L).toInt() - 1).coerceIn(-7, 7)) },
                    onUp   = { c.wbGM(((state.wbGM ?: 0L).toInt() + 1).coerceIn(-7, 7)) })
            }
        },
    )
}

@Composable
private fun WbAdjustRow(label: String, value: String, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onDown, modifier = Modifier.size(30.dp)) { Text("▼", fontSize = 13.sp) }
        Text(value, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold)
        IconButton(onClick = onUp, modifier = Modifier.size(30.dp)) { Text("▲", fontSize = 13.sp) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
) {
    // Disabled (mode-locked) controls render read-only: greyed, no arrows.
    val hasArrows = enabled && (onUp != null || onDown != null)
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .then(if (onLongPress != null)
                Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) } else Modifier)
            .padding(6.dp)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        if (hasArrows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null, modifier = Modifier.size(28.dp)) {
                    Text("▼", fontSize = 12.sp)
                }
                IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null, modifier = Modifier.size(28.dp)) {
                    Text("▲", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RoundAction(label: String, tint: Color = MaterialTheme.colorScheme.onSurface, big: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(if (big) 64.dp else 52.dp),
    ) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = if (big) 24.sp else 16.sp, fontFamily = FontFamily.SansSerif)
    }
}
