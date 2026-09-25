/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License, Version 2.0 (the "License");
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.JpegRefineDebug
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.JpegRefinePreviewMode

/** True while a JPEG Refine rebuild owns the editor. Drag does not set this. */
internal class JpegRefineGate {
    var processing by mutableStateOf(false)
}

internal val LocalJpegRefineGate = staticCompositionLocalOf { JpegRefineGate() }

@Composable
internal fun RawDetailTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
    isJpegSource: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Sharpness ────────────────────────────────────────────────────────────
        DetailSection(title = stringResource(R.string.raw_section_sharpness)) {
            RawSliderRow(
                label = stringResource(R.string.raw_sharpness),
                value = macro.sharpness,
                valueRange = 0f..100f,
                onValueChange = { onMacroChange(macro.withLinkedSharpness(it)) },
                displayValue = "${macro.sharpness.toInt()}",
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = stringResource(R.string.raw_texture),
                value = macro.texture,
                valueRange = -100f..100f,
                onValueChange = { onMacroChange(macro.copy(texture = it)) },
            )
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = stringResource(R.string.raw_clarity),
                value = macro.clarity,
                valueRange = -100f..100f,
                onValueChange = { onMacroChange(macro.copy(clarity = it)) },
            )
        }

        // AI Denoise lives on the export transform bar. isJpegSource stays for callers.
        if (false && isJpegSource) {
            val gate = LocalJpegRefineGate.current
            var shown by remember { mutableFloatStateOf(macro.jpegRefine.strength) }
            var releaseToken by remember { mutableIntStateOf(0) }
            var phase by remember { mutableStateOf("IDLE") }
            LaunchedEffect(gate.processing) {
                if (!gate.processing) phase = "IDLE"
            }
            DetailSection(title = stringResource(R.string.raw_section_jpeg_refine)) {
                RawSliderRow(
                    label = stringResource(R.string.raw_section_jpeg_refine),
                    value = shown,
                    valueRange = 0f..100f,
                    enabled = phase != "PROCESSING",
                    onValueChange = { shown = it },
                    onValueChangeFinished = {
                        if (phase != "PROCESSING") releaseToken++
                    },
                    displayValue = "${shown.toInt()}",
                )
                if (JpegRefineDebug.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
                val tick = remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(JpegRefineDebug.busy) {
                    while (JpegRefineDebug.busy) {
                        tick.longValue = android.os.SystemClock.elapsedRealtime()
                        delay(200)
                    }
                }
                val elapsedSec = if (JpegRefineDebug.busySince == 0L) 0f
                    else (tick.longValue - JpegRefineDebug.busySince) / 1000f
                Text(
                    "Mode: ${JpegRefineDebug.mode}\n" +
                        "Busy: ${JpegRefineDebug.busy}\n" +
                        "Stage: ${JpegRefineDebug.stage}\n" +
                        "Elapsed: ${"%.1f".format(elapsedSec.coerceAtLeast(0f))}s\n" +
                        "Job: queued=${JpegRefineDebug.jobsQueued} cancelled=${JpegRefineDebug.jobsCancelled} completed=${JpegRefineDebug.jobsCompleted}\n" +
                        "Source Key: ${JpegRefineDebug.sourceKey.ifEmpty { "—" }}\n" +
                        "Cache: ${JpegRefineDebug.cacheLine}\n" +
                        "Preview: ${JpegRefineDebug.previewSize}\n" +
                        "Cache size: ${JpegRefineDebug.cacheSize}\n" +
                        "Difference: ${JpegRefineDebug.difference}\n" +
                        "Tiles: ${JpegRefineDebug.tilesDone}/${JpegRefineDebug.tilesTotal}  run ${JpegRefineDebug.tilesRun} skip ${JpegRefineDebug.tilesSkipped}  tile 126 overlap 16\n" +
                        "Bridge: ${JpegRefineDebug.bridge}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    JpegRefinePreviewMode.entries.forEach { mode ->
                        TextButton(onClick = { JpegRefineDebug.mode = mode }) {
                            Text(
                                mode.name,
                                color = if (JpegRefineDebug.mode == mode)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            LaunchedEffect(releaseToken) {
                if (releaseToken == 0) return@LaunchedEffect
                phase = "WAITING_DEBOUNCE"
                val committed = shown
                delay(350)
                if (shown != committed || phase == "PROCESSING") return@LaunchedEffect
                phase = "IDLE"
                onMacroChange(macro.copy(
                    jpegRefine = macro.jpegRefine.copy(strength = committed, touched = true),
                ))
            }
        }

        // Noise Reduction is hidden on the Details tab. The fields stay on the macro.

        // Film Grain section moved to the FX (Effects) tab — see RawEffectsTab.
        // ── Shadow Removal — hidden per request (2026-06-20) ──────────────────────
        // HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        // DetailSection(title = "Shadow Removal") {
        //     Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        //         Checkbox(checked = macro.removeShadows,
        //             onCheckedChange = { onMacroChange(macro.copy(removeShadows = it)) })
        //         Column(modifier = Modifier.weight(1f)) {
        //             Text("Remove Shadows", style = MaterialTheme.typography.bodyMedium)
        //             Text("General scene shadow removal — applied at export",
        //                 style = MaterialTheme.typography.bodySmall,
        //                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        //         }
        //     }
        //     Spacer(Modifier.height(4.dp))
        //     Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        //         Checkbox(checked = macro.removeFaceShadows,
        //             onCheckedChange = { onMacroChange(macro.copy(removeFaceShadows = it)) })
        //         Column(modifier = Modifier.weight(1f)) {
        //             Text("Remove Face Shadows", style = MaterialTheme.typography.bodyMedium)
        //             Text("Portrait-aware shadow removal — applied at export",
        //                 style = MaterialTheme.typography.bodySmall,
        //                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        //         }
        //     }
        // }

        TabResetButton(RawTabId.Detail, macro, onMacroChange)
    }
}

@Composable
private fun DetailCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        content()
    }
}
