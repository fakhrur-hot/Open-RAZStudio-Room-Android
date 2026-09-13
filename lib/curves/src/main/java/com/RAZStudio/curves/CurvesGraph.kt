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
 */

package com.RAZStudio.curves

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.RAZStudio.curves.view.PhotoFilterCurvesControl
import com.RAZStudio.curves.view.PhotoFilterCurvesControl.CurvesToolValue
import kotlin.math.ln

/**
 * Standalone interactive tone-curve graph — the [PhotoFilterCurvesControl] widget
 * hosted WITHOUT a GPUImage backdrop (unlike [ImageCurvesEditor], which requires a
 * bitmap and renders nothing when given null). Hosts use this when the photo is
 * shown by their own renderer (e.g. the RAW editor's GL canvas) and only the graph
 * + channel selector is needed.
 *
 * Drag a tonal segment (blacks / shadows / midtones / highlights / whites) of the
 * active channel up or down to reshape that curve. An optional 256-bin [histogram]
 * is drawn (log-scaled) behind the curves so the user can place points against the
 * real tonal mass of the edited frame.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CurvesGraph(
    state: ImageCurvesEditorState,
    onStateChange: (ImageCurvesEditorState) -> Unit,
    modifier: Modifier = Modifier,
    histogram: IntArray? = null,
    graphHeight: Dp = 200.dp,
    colors: ImageCurvesEditorColors = ImageCurvesEditorDefaults.Colors,
    drawNotActiveCurves: Boolean = true,
    curvesSelectionText: @Composable (curveType: Int) -> Unit = {},
) {
    var curvesView by remember { mutableStateOf<PhotoFilterCurvesControl?>(null) }
    val disallowIntercept = remember { RequestDisallowInterceptTouchEvent() }
    val histColor = colors.guidelinesColor

    // Own the active channel here. The host recreates `state` whenever the curve
    // changes — and ImageCurvesEditorState(controlPoints) always resets activeType
    // to Luminance — so without this the selection would snap back to "All" after
    // the first edit. Re-assert the remembered channel onto the current tool value
    // every recomposition so dragging always edits the channel the user picked.
    var activeType by remember { mutableIntStateOf(state.curvesToolValue.activeType) }
    state.curvesToolValue.activeType = activeType

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().height(graphHeight)) {
            if (histogram != null && histogram.size == 256) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val maxV = (histogram.maxOrNull() ?: 1).coerceAtLeast(1)
                    val logMax = ln(1f + maxV)
                    val binW = size.width / 256f
                    val p = Path().apply {
                        moveTo(0f, size.height)
                        for (i in 0..255) {
                            val n = ln(1f + histogram[i]) / logMax
                            lineTo(i * binW, size.height - n * size.height * 0.9f)
                        }
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(p, color = histColor.copy(alpha = 0.30f))
                }
            }
            AndroidView(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInteropFilter(
                        requestDisallowInterceptTouchEvent = disallowIntercept,
                    ) { ev ->
                        curvesView?.onTouchEvent(ev)
                        disallowIntercept.invoke(true)
                        true
                    },
                factory = { ctx ->
                    PhotoFilterCurvesControl(ctx, state.curvesToolValue).apply {
                        curvesView = this
                        applyColors(colors)
                        setDrawNotActiveCurves(drawNotActiveCurves)
                        setDelegate { onStateChange(state.copy()) }
                        // Curve coordinates are relative to the view's own bounds.
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            setActualArea(0f, 0f, v.width.toFloat(), v.height.toFloat())
                        }
                    }
                },
                update = { v ->
                    v.updateValue(state.curvesToolValue)
                    if (v.width > 0 && v.height > 0) {
                        v.setActualArea(0f, 0f, v.width.toFloat(), v.height.toFloat())
                    }
                    v.applyColors(colors)
                    v.setDrawNotActiveCurves(drawNotActiveCurves)
                    v.setDelegate { onStateChange(state.copy()) }
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Channel selector: Luma / R / G / B.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CurveRadio(colors.lumaCurveColor, CurvesToolValue.CurvesTypeLuminance, activeType, { activeType = it }, curvesSelectionText)
            CurveRadio(colors.redCurveColor, CurvesToolValue.CurvesTypeRed, activeType, { activeType = it }, curvesSelectionText)
            CurveRadio(colors.greenCurveColor, CurvesToolValue.CurvesTypeGreen, activeType, { activeType = it }, curvesSelectionText)
            CurveRadio(colors.blueCurveColor, CurvesToolValue.CurvesTypeBlue, activeType, { activeType = it }, curvesSelectionText)
        }
    }
}

private fun PhotoFilterCurvesControl.applyColors(colors: ImageCurvesEditorColors) {
    setColors(
        lumaCurveColor = colors.lumaCurveColor.toArgb(),
        redCurveColor = colors.redCurveColor.toArgb(),
        greenCurveColor = colors.greenCurveColor.toArgb(),
        blueCurveColor = colors.blueCurveColor.toArgb(),
        defaultCurveColor = colors.defaultCurveColor.toArgb(),
        guidelinesColor = colors.guidelinesColor.toArgb(),
    )
}

@Composable
private fun RowScope.CurveRadio(
    color: Color,
    type: Int,
    activeType: Int,
    onSelect: (Int) -> Unit,
    curvesSelectionText: @Composable (type: Int) -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f, false),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RadioButton(
            selected = activeType == type,
            onClick = { onSelect(type) },
            colors = RadioButtonDefaults.colors(selectedColor = color, unselectedColor = color),
        )
        CompositionLocalProvider(LocalContentColor provides color) {
            curvesSelectionText(type)
        }
    }
}
