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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raz.razstudio.lib.raw.RawExif
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2-integration §2.1 — full EXIF info bottom sheet shown from the editor toolbar
 * `ⓘ` action. Renders the structured fields from [RawExif] in a scrollable list.
 *
 * Also surfaces a "Strip GPS on save" toggle whose state is propagated through
 * [onStripGpsToggle] so the caller can update `UserMacro.stripGps`. The toggle is
 * hidden when the source has no GPS data (saves a row of UI noise).
 *
 * @param exif              EXIF block; null hides the sheet content (caller normally
 *                          gates the open-sheet action on `exif != null` so this is
 *                          a defensive fallback).
 * @param stripGpsOnSave    Current [com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro.stripGps] value.
 * @param onStripGpsToggle  Caller updates the macro when the user flips the toggle.
 * @param onDismiss         Called when the user dismisses the sheet (swipe down, scrim tap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExifInfoSheet(
    exif: RawExif?,
    stripGpsOnSave: Boolean,
    onStripGpsToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Photo info",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "EXIF read from the RAW file",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (exif == null) {
                Text(
                    text = "EXIF unavailable for this file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExifSection("Camera") {
                    KeyValueRow("Make", exif.make.takeIf { it.isNotBlank() } ?: "—")
                    KeyValueRow("Model", exif.model.takeIf { it.isNotBlank() } ?: "—")
                    KeyValueRow("Lens", exif.lensModel.takeIf { it.isNotBlank() } ?: "—")
                    KeyValueRow("Lens make", exif.lensMake.takeIf { it.isNotBlank() } ?: "—")
                }
                Spacer(Modifier.height(8.dp))
                ExifSection("Exposure") {
                    KeyValueRow("ISO", if (exif.iso > 0) exif.iso.toString() else "—")
                    KeyValueRow("Shutter", exif.shutterDisplay.takeIf { it.isNotBlank() } ?: "—")
                    KeyValueRow(
                        "Aperture",
                        if (exif.apertureF > 0f) "f/%.1f".format(exif.apertureF) else "Manual",
                    )
                    KeyValueRow(
                        "Focal length",
                        when {
                            exif.focalMm > 0f && exif.focalMm35eq > 0f ->
                                "%.0f mm (%.0f mm eq.)".format(exif.focalMm, exif.focalMm35eq)
                            exif.focalMm > 0f -> "%.0f mm".format(exif.focalMm)
                            else -> "—"
                        },
                    )
                    if (exif.exposureCompensationEv != 0f) {
                        KeyValueRow("Exposure comp.", "%+.1f EV".format(exif.exposureCompensationEv))
                    }
                }
                if (exif.hasGps) {
                    Spacer(Modifier.height(8.dp))
                    ExifSection("Location") {
                        KeyValueRow(
                            "Coordinates",
                            "%.6f, %.6f".format(exif.gpsLat, exif.gpsLon),
                        )
                        if (exif.gpsAlt != 0.0) {
                            KeyValueRow("Altitude", "%.0f m".format(exif.gpsAlt))
                        }
                        Spacer(Modifier.height(8.dp))
                        StripGpsToggleRow(
                            enabled = stripGpsOnSave,
                            onToggle = onStripGpsToggle,
                        )
                    }
                }
                if (exif.timestampEpochS > 0) {
                    Spacer(Modifier.height(8.dp))
                    ExifSection("Captured") {
                        KeyValueRow(
                            "Timestamp",
                            formatTimestamp(exif.timestampEpochS),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ExifSection("Sensor") {
                    KeyValueRow("White level", exif.whiteLevel.toString())
                    KeyValueRow(
                        "Black levels",
                        exif.blackLevelsPerChannel.joinToString(", "),
                    )
                    KeyValueRow(
                        "As-shot WB",
                        exif.cameraWhiteBalanceAsShot.joinToString(", ") { "%.3f".format(it) },
                    )
                    if (exif.softwareTag.isNotBlank()) {
                        KeyValueRow("Software", exif.softwareTag)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExifSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    HorizontalDivider()
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.6f),
        )
    }
}

@Composable
private fun StripGpsToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Strip GPS on save",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Removes geolocation tags from the output file",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.padding(start = 8.dp)) {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

private fun formatTimestamp(epochSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .format(Date(epochSeconds * 1000L))
