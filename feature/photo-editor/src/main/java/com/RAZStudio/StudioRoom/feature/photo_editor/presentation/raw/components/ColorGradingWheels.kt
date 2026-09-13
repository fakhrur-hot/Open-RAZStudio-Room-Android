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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.ColorWheel
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.sqrt

/**
 * DaVinci / OpenShot-style 4-way colour grading wheels (Option C).
 *
 * Four trackballs — **Global · Shadows · Midtones · Highlights** — drive the
 * Lift/Gamma/Gain/Offset math already wired through the GL preview shader and
 * the CPU export kernel (see applyColorGrading in shader_sources.cpp /
 * applyColorGradingP in apply_macro.cpp). Each wheel stores a per-channel R/G/B
 * push in [-50..+50] plus a strength ("Sat") in [-100..+100].
 *
 * The disc uses an **RGB-trackball** mapping (three unit axes 120° apart):
 * Red points up, Green lower-left, Blue lower-right. Because the three axes
 * form a tight frame in 2-D, the thumb position ↔ (r,g,b) mapping is exactly
 * invertible, so a wheel reloaded from a sidecar lands the thumb back where the
 * user left it.
 */

// Unit axes (math convention: +x right, +y up). R up, G lower-left, B lower-right.
private const val AXIS_COS = 0.8660254f   // cos 30°
private val R_AXIS = Offset(0f, 1f)
private val G_AXIS = Offset(-AXIS_COS, -0.5f)
private val B_AXIS = Offset(AXIS_COS, -0.5f)
private const val RGB_SCALE = 50f         // disc-edge radius == ±50 channel push

/** Thumb (dx,dy in [-1..1], math y-up) → per-channel push scaled to ±50. */
private fun thumbToRgb(dx: Float, dy: Float): Triple<Float, Float, Float> {
    val r = (dx * R_AXIS.x + dy * R_AXIS.y) * RGB_SCALE
    val g = (dx * G_AXIS.x + dy * G_AXIS.y) * RGB_SCALE
    val b = (dx * B_AXIS.x + dy * B_AXIS.y) * RGB_SCALE
    return Triple(
        r.coerceIn(-RGB_SCALE, RGB_SCALE),
        g.coerceIn(-RGB_SCALE, RGB_SCALE),
        b.coerceIn(-RGB_SCALE, RGB_SCALE),
    )
}

/** (r,g,b) push → thumb position via the tight-frame pseudo-inverse (2/3·Σ). */
private fun rgbToThumb(r: Float, g: Float, b: Float): Offset {
    val cr = r / RGB_SCALE; val cg = g / RGB_SCALE; val cb = b / RGB_SCALE
    val dx = (2f / 3f) * (cr * R_AXIS.x + cg * G_AXIS.x + cb * B_AXIS.x)
    val dy = (2f / 3f) * (cr * R_AXIS.y + cg * G_AXIS.y + cb * B_AXIS.y)
    return Offset(dx, dy)
}

@Composable
internal fun ColorGradingSection(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf("Global", "Shadows", "Midtones", "Highlights")
    var selected by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        ScrollableTabRow(
            selectedTabIndex = selected,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
        ) {
            tabs.forEachIndexed { i, name ->
                Tab(
                    selected = selected == i,
                    onClick = { selected = i },
                    text = { Text(name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        when (selected) {
            // Global → cgGlobal ColorWheel holder (offset).
            0 -> ColorWheelControl(
                label = "Global (offset)",
                r = macro.cgGlobal.r, g = macro.cgGlobal.g, b = macro.cgGlobal.b,
                sat = macro.cgGlobal.sat,
                onChange = { nr, ng, nb ->
                    onMacroChange(macro.copy(cgGlobal = macro.cgGlobal.copy(r = nr, g = ng, b = nb)))
                },
                onSat = { onMacroChange(macro.copy(cgGlobal = macro.cgGlobal.copy(sat = it))) },
                onReset = { onMacroChange(macro.copy(cgGlobal = ColorWheel())) },
            )
            // Shadows → cgShadows (lift).
            1 -> ColorWheelControl(
                label = "Shadows (lift)",
                r = macro.cgShadows.r, g = macro.cgShadows.g, b = macro.cgShadows.b,
                sat = macro.cgShadows.sat,
                onChange = { nr, ng, nb ->
                    onMacroChange(macro.copy(cgShadows = macro.cgShadows.copy(r = nr, g = ng, b = nb)))
                },
                onSat = { onMacroChange(macro.copy(cgShadows = macro.cgShadows.copy(sat = it))) },
                onReset = { onMacroChange(macro.copy(cgShadows = ColorWheel())) },
            )
            // Midtones → cgMidtones (gamma).
            2 -> ColorWheelControl(
                label = "Midtones (gamma)",
                r = macro.cgMidtones.r, g = macro.cgMidtones.g, b = macro.cgMidtones.b,
                sat = macro.cgMidtones.sat,
                onChange = { nr, ng, nb ->
                    onMacroChange(macro.copy(cgMidtones = macro.cgMidtones.copy(r = nr, g = ng, b = nb)))
                },
                onSat = { onMacroChange(macro.copy(cgMidtones = macro.cgMidtones.copy(sat = it))) },
                onReset = { onMacroChange(macro.copy(cgMidtones = ColorWheel())) },
            )
            // Highlights → cgHighlights (gain).
            else -> ColorWheelControl(
                label = "Highlights (gain)",
                r = macro.cgHighlights.r, g = macro.cgHighlights.g, b = macro.cgHighlights.b,
                sat = macro.cgHighlights.sat,
                onChange = { nr, ng, nb ->
                    onMacroChange(macro.copy(cgHighlights = macro.cgHighlights.copy(r = nr, g = ng, b = nb)))
                },
                onSat = { onMacroChange(macro.copy(cgHighlights = macro.cgHighlights.copy(sat = it))) },
                onReset = { onMacroChange(macro.copy(cgHighlights = ColorWheel())) },
            )
        }
    }
}

@Composable
private fun ColorWheelControl(
    label: String,
    r: Float, g: Float, b: Float,
    sat: Float,
    onChange: (Float, Float, Float) -> Unit,
    onSat: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            ColorDisc(
                r = r, g = g, b = b,
                onChange = onChange,
                onReset = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }

        Spacer(Modifier.height(8.dp))
        // Strength / saturation of the wheel's contribution.
        RawSliderRow(
            label = "Strength",
            value = sat,
            valueRange = -100f..100f,
            step = 1f,
            onValueChange = onSat,
            displayValue = "${sat.toInt()}",
        )
        Spacer(Modifier.height(4.dp))
        RawSliderRow(
            label = "R",
            value = r,
            valueRange = -RGB_SCALE..RGB_SCALE,
            step = 1f,
            onValueChange = { onChange(it, g, b) },
            displayValue = "${r.toInt()}",
        )
        RawSliderRow(
            label = "G",
            value = g,
            valueRange = -RGB_SCALE..RGB_SCALE,
            step = 1f,
            onValueChange = { onChange(r, it, b) },
            displayValue = "${g.toInt()}",
        )
        RawSliderRow(
            label = "B",
            value = b,
            valueRange = -RGB_SCALE..RGB_SCALE,
            step = 1f,
            onValueChange = { onChange(r, g, it) },
            displayValue = "${b.toInt()}",
        )
    }
}

@Composable
private fun ColorDisc(
    r: Float, g: Float, b: Float,
    onChange: (Float, Float, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hue ring COMPUTED from the same RGB-trackball mapping the drag uses, so
    // the colour under any point on the disc is exactly the tint you get by
    // dragging there (red at top, yellow upper-left, green lower-left, cyan
    // bottom, blue lower-right, magenta upper-right). A generic rainbow ring
    // mismatched this and made blue/yellow look swapped. sweepGradient lays the
    // stops out starting at 3 o'clock, clockwise (screen y-down).
    val ring = remember {
        val n = 36
        (0..n).map { i ->
            val phi = (i.toFloat() / n) * 2f * Math.PI.toFloat()  // clockwise from 3 o'clock
            val cosf = kotlin.math.cos(phi); val sinf = kotlin.math.sin(phi)
            // Screen unit (cosf, sinf, y-down) → drag thumb (dx, dy y-up).
            val dx = cosf; val dy = -sinf
            val rDir = dx * R_AXIS.x + dy * R_AXIS.y
            val gDir = dx * G_AXIS.x + dy * G_AXIS.y
            val bDir = dx * B_AXIS.x + dy * B_AXIS.y
            fun ch(x: Float) = (0.5f + 0.55f * x).coerceIn(0f, 1f)
            Color(ch(rDir), ch(gDir), ch(bDir))
        }
    }
    val ringColor = MaterialTheme.colorScheme.outline
    val thumbStroke = MaterialTheme.colorScheme.onSurface
    val thumbFill = MaterialTheme.colorScheme.surface
    // pointerInput(Unit) never restarts, so the gesture blocks below would keep
    // the FIRST composition's lambdas — whose captured `macro` still had the
    // wheel's original Strength. Every disc drag then re-emitted that stale
    // strength ("Strength resets when I move the colour point"). Read the
    // latest callbacks through rememberUpdatedState instead.
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnReset by rememberUpdatedState(onReset)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnReset() })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val cx = w / 2f; val cy = h / 2f
                    val rad = (minOf(w, h) / 2f)
                    var dx = (change.position.x - cx) / rad
                    // screen y is down → flip to math y-up.
                    var dy = -(change.position.y - cy) / rad
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > 1f) { dx /= len; dy /= len }
                    val (nr, ng, nb) = thumbToRgb(dx, dy)
                    currentOnChange(nr, ng, nb)
                }
            },
    ) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val rad = minOf(size.width, size.height) / 2f

        // Hue ring.
        drawCircle(
            brush = Brush.sweepGradient(colors = ring, center = Offset(cx, cy)),
            radius = rad,
            center = Offset(cx, cy),
        )
        // Desaturate toward the centre (neutral).
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(thumbFill, Color.Transparent),
                center = Offset(cx, cy),
                radius = rad,
            ),
            radius = rad,
            center = Offset(cx, cy),
        )
        // Rim.
        drawCircle(
            color = ringColor,
            radius = rad,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )

        // Thumb.
        val t = rgbToThumb(r, g, b)
        val tx = cx + t.x * rad
        val ty = cy - t.y * rad   // math y-up → screen y-down
        drawCircle(color = thumbFill, radius = 9f, center = Offset(tx, ty))
        drawCircle(
            color = thumbStroke, radius = 9f, center = Offset(tx, ty),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
    }
}
