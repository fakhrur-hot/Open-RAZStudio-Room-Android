/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.ui.widget.image.ImageNotPickedWidget
import com.RAZStudio.StudioRoom.core.settings.presentation.model.UiSettingsState
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskBrushMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun RawEditorCanvas(
    modifier: Modifier,
    component: RawEditorComponent,
    uiState: RawPipelineState,
    settingsState: UiSettingsState,
    canvasWidth: Int,
    onCanvasWidthChange: (Int) -> Unit,
    canvasHeight: Int,
    onCanvasHeightChange: (Int) -> Unit,
    canvasScale: Float,
    onCanvasScaleChange: (Float) -> Unit,
    canvasOffset: Offset,
    onCanvasOffsetChange: (Offset) -> Unit,
    imageAspect: Float,
    onImageAspectChange: (Float) -> Unit,
    isComparing: Boolean,
    onComparingChange: (Boolean) -> Unit,
    launchRawPicker: () -> Unit,
    isToneCurvesTab: Boolean,
    isMaskTab: Boolean,
    isVignetteCenterMode: Boolean,
    lutPath: String?,
    toneCurveLut: ByteArray?,
    glViewForHistogram: androidx.compose.runtime.MutableState<com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3GlSurfaceView?>,
    pauseGradedBackdrop: Bitmap?,
    onPauseBackdropChange: (Bitmap?) -> Unit,
    onBakeStateChange: (String, String) -> Unit
) {
    val isPreviewReady = uiState is RawPipelineState.PreviewReady ||
            uiState is RawPipelineState.FullResProcessing ||
            uiState is RawPipelineState.FullResReady

    val isMaskModeActive by component.masking.isMaskModeActive.collectAsState()
    val brushMode by component.masking.brushMode.collectAsState()
    val brushSize by component.masking.brushSize.collectAsState()
    val brushIntensity by component.masking.brushIntensity.collectAsState()
    val brushFeather by component.masking.brushFeather.collectAsState()
    val maskBitmap by component.masking.maskBitmap.collectAsState()
    val maskDirty by component.masking.maskDirty.collectAsState()

    val healActive by component.healing.healActive.collectAsState()
    val healRadiusPx by component.healing.healRadiusPx.collectAsState()
    val healedOverlay by component.healing.healedOverlay.collectAsState()
    val isHealing by component.healing.isHealing.collectAsState()

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        onCanvasScaleChange((canvasScale * zoomChange).coerceIn(0.5f, 8f))
        onCanvasOffsetChange(canvasOffset + offsetChange)
    }

    Box(
        modifier = modifier
            .background(settingsState.letterboxColor)
            .clipToBounds()
            .onSizeChanged { onCanvasWidthChange(it.width); onCanvasHeightChange(it.height) }
            .then(
                when {
                    isMaskModeActive &&
                            (brushMode == MaskBrushMode.Draw || brushMode == MaskBrushMode.Erase) &&
                            isPreviewReady -> Modifier.pointerInput(
                        brushSize, brushIntensity, brushFeather, brushMode, canvasWidth, canvasHeight,
                        canvasScale, canvasOffset // Re-bind on transform so screenToBmp is fresh
                    ) {
                        awaitEachGesture {
                            val bmp = maskBitmap ?: run {
                                val neutral = component.neutralBitmap ?: return@awaitEachGesture
                                val newBmp = android.graphics.Bitmap.createBitmap(
                                    neutral.width, neutral.height, android.graphics.Bitmap.Config.ARGB_8888,
                                )
                                component.masking.updateMask(newBmp)
                                newBmp
                            }
                            val bmCanvas = android.graphics.Canvas(bmp)
                            val paint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                strokeWidth = brushSize
                                color = android.graphics.Color.WHITE
                                alpha = (255 * brushIntensity).roundToInt().coerceIn(0, 255)
                                if (brushFeather > 0.01f) {
                                    maskFilter = android.graphics.BlurMaskFilter(
                                        brushSize * brushFeather * 0.5f,
                                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                                    )
                                }
                                if (brushMode == MaskBrushMode.Erase) {
                                    xfermode = android.graphics.PorterDuffXfermode(
                                        android.graphics.PorterDuff.Mode.DST_OUT,
                                    )
                                }
                            }

                            fun screenToBmp(pos: Offset): android.graphics.PointF {
                                val bW = bmp.width.toFloat()
                                val bH = bmp.height.toFloat()
                                val cW = canvasWidth.toFloat().coerceAtLeast(1f)
                                val cH = canvasHeight.toFloat().coerceAtLeast(1f)
                                val cx = cW / 2f; val cy = cH / 2f
                                val ix = (pos.x - canvasOffset.x - cx) / canvasScale + cx
                                val iy = (pos.y - canvasOffset.y - cy) / canvasScale + cy
                                val imgAspect = bW / bH
                                val imgW: Float; val imgH: Float
                                if (imgAspect > cW / cH) { imgW = cW; imgH = cW / imgAspect }
                                else { imgW = cH * imgAspect; imgH = cH }
                                val left = (cW - imgW) / 2f
                                val top  = (cH - imgH) / 2f
                                return android.graphics.PointF(
                                    ((ix - left) * bW / imgW).coerceIn(0f, bW - 1f),
                                    ((iy - top) * bH / imgH).coerceIn(0f, bH - 1f),
                                )
                            }

                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var last = screenToBmp(down.position)
                            var started = false
                            var didTransform = false
                            var twoFinger = false
                            var prevCentroid = Offset.Zero
                            var prevDist = 0f

                            while (true) {
                                val evt = awaitPointerEvent()
                                val pressed = evt.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val c = (pressed.fold(Offset.Zero) { a, p -> a + p.position }) /
                                            pressed.size.toFloat()
                                    val dist = (pressed[1].position - pressed[0].position).getDistance()
                                    if (twoFinger && prevDist > 0f) {
                                        onCanvasScaleChange((canvasScale * (dist / prevDist)).coerceIn(0.5f, 8f))
                                        val newOffset = canvasOffset + (c - prevCentroid)
                                        val cW = canvasWidth.toFloat().coerceAtLeast(1f)
                                        val cH = canvasHeight.toFloat().coerceAtLeast(1f)
                                        val fitW = minOf(cW, cH * imageAspect)
                                        val maxDx = ((fitW * canvasScale - cW) / 2f).coerceAtLeast(0f)
                                        val maxDy = (((fitW / imageAspect.coerceAtLeast(0.001f)) * canvasScale - cH) / 2f).coerceAtLeast(0f)
                                        onCanvasOffsetChange(Offset(
                                            newOffset.x.coerceIn(-maxDx, maxDx),
                                            newOffset.y.coerceIn(-maxDy, maxDy),
                                        ))
                                    }
                                    twoFinger = true; didTransform = true
                                    prevCentroid = c; prevDist = dist
                                    pressed.forEach { it.consume() }
                                } else {
                                    val ch = pressed.first()
                                    if (didTransform) { ch.consume(); continue }
                                    val curr = screenToBmp(ch.position)
                                    if (!started) {
                                        bmCanvas.drawLine(curr.x, curr.y, curr.x, curr.y, paint)
                                        started = true
                                    } else {
                                        bmCanvas.drawLine(last.x, last.y, curr.x, curr.y, paint)
                                    }
                                    last = curr
                                    component.masking.updateMask(bmp)
                                    ch.consume()
                                }
                            }
                            if (!started && !didTransform) {
                                val p = screenToBmp(down.position)
                                bmCanvas.drawLine(p.x, p.y, p.x, p.y, paint)
                                component.masking.updateMask(bmp)
                            }
                        }
                    }
                    // ... (rest of the pointer input branches: ColorSelect, VignetteCenter, Heal, Default)
                    else -> Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = uiState) {
            is RawPipelineState.AwaitingWorkspaceChoice -> {
                // Workspace Choice UI
            }
            is RawPipelineState.Idle -> {
                // Idle UI
            }
            is RawPipelineState.PreviewLoading -> {
                // Loading UI
            }
            is RawPipelineState.PreviewReady,
            is RawPipelineState.FullResProcessing,
            is RawPipelineState.FullResReady -> {
                // GL Preview and Overlays
            }
            is RawPipelineState.Error -> {
                Text(
                    text     = state.message,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
