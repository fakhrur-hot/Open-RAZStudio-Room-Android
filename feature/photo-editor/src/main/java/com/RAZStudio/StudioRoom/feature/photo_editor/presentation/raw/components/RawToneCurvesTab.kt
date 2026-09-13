/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.curves.CurvesGraph
import com.RAZStudio.curves.ImageCurvesEditorState
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.FilmCurve
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro

@Composable
internal fun RawToneCurvesTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    previewBitmap: Bitmap?,
    gradedHistogram: IntArray? = null,
    modifier: Modifier = Modifier,
) {
    var curvesState by remember { mutableStateOf(ImageCurvesEditorState(macro.toneCurvePoints)) }

    // Sync state when macro is loaded externally (e.g., action applied)
    LaunchedEffect(macro.toneCurvePoints) {
        if (curvesState.controlPoints != macro.toneCurvePoints) {
            curvesState = ImageCurvesEditorState(macro.toneCurvePoints)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Standalone interactive graph (no GPUImage backdrop — the photo is shown by
        // the editor's own GL canvas). The graded-frame luma histogram is drawn behind
        // the curves so points can be placed against real tonal mass.
        CurvesGraph(
            state = curvesState,
            onStateChange = { newState ->
                curvesState = newState
                onMacroChange(macro.copy(toneCurvePoints = newState.controlPoints))
            },
            histogram = gradedHistogram,
            graphHeight = 200.dp,
            curvesSelectionText = { curveType ->
                Text(
                    text = when (curveType) {
                        0 -> stringResource(R.string.raw_curve_channel_all)
                        1 -> stringResource(R.string.raw_curve_channel_r)
                        2 -> stringResource(R.string.raw_curve_channel_g)
                        3 -> stringResource(R.string.raw_curve_channel_b)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Film curve",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Quick filmic contrast under the freehand points. Fine-tune with the graph above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilmPresetChip("Film S", FilmCurve.FilmSCurve, macro, onMacroChange)
            FilmPresetChip("Soft", FilmCurve.SoftContrast, macro, onMacroChange)
            FilmPresetChip("High", FilmCurve.HighContrast, macro, onMacroChange)
            FilmPresetChip("Cinema", FilmCurve.CinematicRolloff, macro, onMacroChange)
            FilterChip(
                selected = macro.filmCurve == FilmCurve(),
                onClick = { onMacroChange(macro.copy(filmCurve = FilmCurve())) },
                label = { Text("Off") },
            )
        }

        val fc = macro.filmCurve
        RawSliderRow(
            label = "Contrast",
            value = fc.contrast,
            valueRange = 0f..100f,
            step = 1f,
            displayValue = "${fc.contrast.toInt()}",
            onValueChange = {
                onMacroChange(macro.copy(filmCurve = fc.copy(contrast = it)))
            },
        )
        RawSliderRow(
            label = "Pivot",
            value = fc.pivot,
            valueRange = 0.05f..0.95f,
            step = 0.01f,
            displayValue = "%.2f".format(fc.pivot),
            enabled = fc.contrast > 0f,
            onValueChange = {
                onMacroChange(macro.copy(filmCurve = fc.copy(pivot = it)))
            },
        )
        RawSliderRow(
            label = "HL Roll-off",
            value = fc.highlightKnee,
            valueRange = 0f..100f,
            step = 1f,
            displayValue = "${fc.highlightKnee.toInt()}",
            onValueChange = {
                onMacroChange(macro.copy(filmCurve = fc.copy(highlightKnee = it)))
            },
        )
        RawSliderRow(
            label = "Shadow Toe",
            value = fc.shadowToe,
            valueRange = 0f..100f,
            step = 1f,
            displayValue = "${fc.shadowToe.toInt()}",
            onValueChange = {
                onMacroChange(macro.copy(filmCurve = fc.copy(shadowToe = it)))
            },
        )
    }
}

@Composable
private fun FilmPresetChip(
    label: String,
    preset: FilmCurve,
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
) {
    FilterChip(
        selected = macro.filmCurve == preset,
        onClick = { onMacroChange(macro.copy(filmCurve = preset)) },
        label = { Text(label) },
    )
}

/**
 * Auto-anchor the master tone curve to histogram percentiles (Levels-style):
 *   black anchor = brightness where cumulative histogram crosses 0.5%
 *   white anchor = brightness where cumulative histogram crosses 99.5%
 * Shadows / midtones / highlights are linearly placed between the new anchors
 * so the curve stays monotone with no kinks. R/G/B channel curves are not
 * touched. Returns null if no histogram is available.
 */
@Suppress("unused")
private fun autoAnchorMasterCurve(
    currentPoints: List<List<Float>>,
    gradedHistogram: IntArray?,
): List<List<Float>>? {
    if (gradedHistogram == null || gradedHistogram.size != 256) return null
    val total = gradedHistogram.sumOf { it.toLong() }
    if (total <= 0L) return null
    val lowCount  = (total * 0.005).toLong()
    val highCount = (total * 0.995).toLong()
    var acc = 0L
    var blackIdx = 0
    var whiteIdx = 255
    for (i in 0..255) {
        acc += gradedHistogram[i]
        if (acc >= lowCount)  { blackIdx = i; break }
    }
    acc = 0L
    for (i in 0..255) {
        acc += gradedHistogram[i]
        if (acc >= highCount) { whiteIdx = i; break }
    }
    // Need a useful range; if the histogram is empty/degenerate, do nothing.
    if (whiteIdx <= blackIdx + 4) return null
    val xBlack = blackIdx / 255f
    val xWhite = whiteIdx / 255f
    // Compute the master curve y at x={0, .25, .5, .75, 1} by mapping each x
    // through a linear stretch: y = (x - xBlack) / (xWhite - xBlack), clamped.
    fun stretch(x: Float): Float = ((x - xBlack) / (xWhite - xBlack)).coerceIn(0f, 1f)
    val newMaster = listOf(
        stretch(0f), stretch(0.25f), stretch(0.5f), stretch(0.75f), stretch(1f),
    )
    val out = ArrayList<List<Float>>(currentPoints.size)
    out.add(newMaster)
    for (i in 1 until currentPoints.size) out.add(currentPoints[i])
    return out
}
