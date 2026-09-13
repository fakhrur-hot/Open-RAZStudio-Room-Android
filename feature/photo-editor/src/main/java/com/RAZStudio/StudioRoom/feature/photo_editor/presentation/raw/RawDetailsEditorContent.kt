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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.ArrowBack
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.RawDetailTab
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3PreviewComposable

/**
 * Final-quality Details editor workspace.
 *
 * Layout mirrors [RawEditorContent]: a letterboxed canvas hosts the
 * live FP16 GL preview (driven by the shadow [RawEditorComponent]'s
 * pipeline against the full-res Stage A TIFF), a draggable handle
 * sits between canvas and panel, and the panel hosts the existing
 * Details-tab sliders. The Details editor intentionally exposes ONLY
 * the Details tab — every other tab's contribution comes from the
 * shadow editor's pre-existing action stack (with prior Details
 * cards stripped on entry — see [RawDetailsEditorComponent.init]).
 *
 * Pan + zoom on the canvas are supported; brush / mask / vignette-
 * center interactions from the main editor's CanvasBox are deliberately
 * omitted — those belong to other tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawDetailsEditorContent(component: RawDetailsEditorComponent) {
    val settingsState = com.RAZStudio.StudioRoom.core.settings.presentation.provider
        .LocalSettingsState.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val shadow = component.shadowEditor
    val stageATifPath by shadow.stageATifPathFlow.collectAsState()
    val params by shadow.shaderParamsFlow.collectAsState()
    val lutPath by shadow.lutCubePathFlow.collectAsState()
    val toneCurveLut by shadow.toneCurveLutFlow.collectAsState()
    val subjectMask by shadow.segmentationMasksV3.collectAsState()
    val isPreviewReady by shadow.fullResReady.collectAsState()

    var canvasFraction by remember { mutableFloatStateOf(0.55f) }
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var imageAspect by remember { mutableFloatStateOf(1f) }

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        canvasScale = (canvasScale * zoomChange).coerceIn(0.5f, 8f)
        canvasOffset += offsetChange
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.raw_details_editor)) },
                navigationIcon = {
                    IconButton(onClick = component.onGoBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomActionRow(
                onApply = {
                    component.triggerApplyAndSave()
                    component.onGoBack()
                },
            )
        },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val totalHeightPx = constraints.maxHeight.toFloat()
            val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            val canvasContent: @Composable (Modifier) -> Unit = { canvasModifier ->
                Box(
                    modifier = canvasModifier
                        .background(settingsState.letterboxColor)
                        .clipToBounds()
                        .transformable(state = transformableState),
                    contentAlignment = Alignment.Center,
                ) {
                    val pathSnapshot = stageATifPath
                    if (pathSnapshot != null) {
                        RawV3PreviewComposable(
                            stageATifPath = pathSnapshot,
                            params = params,
                            lutCubePath = lutPath,
                            toneCurveLut = toneCurveLut,
                            subjectMask = subjectMask,
                            onImageSize = { w, h ->
                                if (h > 0) imageAspect = w.toFloat() / h.toFloat()
                            },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .aspectRatio(imageAspect)
                                .graphicsLayer {
                                    scaleX = canvasScale
                                    scaleY = canvasScale
                                    translationX = canvasOffset.x
                                    translationY = canvasOffset.y
                                },
                        )
                    }
                }
            }

            val panelContent: @Composable (Modifier) -> Unit = { panelModifier ->
                Surface(
                    modifier = panelModifier,
                    tonalElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RawDetailTab(
                            macro = component.detailsMacro,
                            onMacroChange = component::updateDetailsMacro,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (isLandscape) {
                Row(modifier = Modifier.fillMaxSize()) {
                    canvasContent(
                        Modifier
                            .fillMaxHeight()
                            .weight(canvasFraction),
                    )
                    if (isPreviewReady) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state = rememberDraggableState { delta ->
                                        canvasFraction = (canvasFraction + delta / totalWidthPx)
                                            .coerceIn(0.25f, 0.75f)
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                }
                            }
                        }
                    }
                    panelContent(
                        Modifier
                            .fillMaxHeight()
                            .weight(1f - canvasFraction),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    canvasContent(
                        Modifier
                            .fillMaxWidth()
                            .weight(canvasFraction),
                    )
                    if (isPreviewReady) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    state = rememberDraggableState { delta ->
                                        canvasFraction = (canvasFraction + delta / totalHeightPx)
                                            .coerceIn(0.25f, 0.80f)
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                }
                            }
                        }
                    }
                    panelContent(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f - canvasFraction),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomActionRow(onApply: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        ) {
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.raw_details_apply))
            }
        }
    }
}

