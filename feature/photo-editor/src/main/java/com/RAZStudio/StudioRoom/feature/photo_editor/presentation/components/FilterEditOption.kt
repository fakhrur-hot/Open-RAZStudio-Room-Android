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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.toBitmap
import com.RAZStudio.curves.ImageCurvesEditor
import com.RAZStudio.curves.ImageCurvesEditorState
import com.RAZStudio.StudioRoom.core.data.utils.toCoil
import com.RAZStudio.StudioRoom.core.domain.model.FileModel
import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiCubeLutFilter
import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.BlurCircular
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.Cube
import com.RAZStudio.StudioRoom.core.resources.icons.Tonality
import com.RAZStudio.StudioRoom.core.resources.icons.Stacks
import com.RAZStudio.StudioRoom.core.resources.icons.Tune
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ProvideFilterPreview
import com.RAZStudio.StudioRoom.core.ui.widget.buttons.ShowOriginalButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedTopAppBar
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedTopAppBarType
import com.RAZStudio.StudioRoom.core.ui.widget.image.Picture
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.transparencyChecker
import com.RAZStudio.StudioRoom.core.ui.widget.text.marquee
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions.ActionCard
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions.ActionsSet
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions.ActionsStorage
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions.ActionsTabContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.actions.ToneCurvesApplicator
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur.BlurAiProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur.BlurSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur.BlurTabContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.blur.DofMode
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details.DetailsProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details.DetailsSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.details.DetailsTabContent
import com.RAZStudio.StudioRoom.core.resources.icons.CenterFocusStrong
import com.RAZStudio.StudioRoom.core.resources.icons.Gradient
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LayersProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LayersSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LinearGradientSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.LinearGradientTabContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.VignetteSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.layers.VignetteTabContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.AiLutProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutCategory
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutSelection
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.LutTabContent
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.rememberAiLutProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.rememberZeroDceProcessor
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.rememberLocalLutRepository
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut.rememberLutCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

private enum class FilterTab { Actions, LUT, ToneCurves, Blur, Details, Vignette, LinearGradient }

@Composable
fun FilterEditOption(
    visible: Boolean,
    onDismiss: () -> Unit,
    useScaffold: Boolean,
    bitmap: Bitmap?,
    /** Full-resolution source bitmap for export. When null, [bitmap] is used (preview quality). */
    fullResBitmap: Bitmap? = null,
    workspaceUri: Uri? = null,
    onGetBitmap: (Bitmap) -> Unit,
    onRequestMappingFilters: (List<UiFilter<*>>) -> List<com.RAZStudio.StudioRoom.core.domain.transformation.Transformation<Bitmap>>,
    transformWithFilter: suspend (Bitmap, UiFilter<*>) -> Bitmap,
) {
    var stateBitmap by remember(bitmap, visible) { mutableStateOf(if (!visible) null else bitmap) }
    var showOriginal by remember { mutableStateOf(false) }
    ProvideFilterPreview(stateBitmap)

    // ── AI processors and mask state — live OUTSIDE AnimatedVisibility ────────
    // This ensures masks are computed eagerly the moment a photo is loaded,
    // before the user opens the filter tab. Priority boosts naturally once the
    // filter UI is visible (UI thread awaits the coroutine results).
    val aiLutProcessor = rememberAiLutProcessor()
    val blurAiProcessor = remember { BlurAiProcessor() }
    val detailsProcessor = remember { DetailsProcessor() }
    val layersProcessor = remember { LayersProcessor() }

    var aiMask by remember { mutableStateOf<FloatArray?>(null) }
    var sharpEdgeMask by remember { mutableStateOf<FloatArray?>(null) }
    var aiMaskSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isComputingMask by remember { mutableStateOf(false) }
    var aiPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aiBlurPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Blur filter applied to compositeBase/bitmap — cached so AI overlay re-uses it without
    // re-running the (potentially expensive) filter for every edgeBlur/dofMode slider change.
    var blurredForAiPreview by remember { mutableStateOf<Bitmap?>(null) }

    // Eager: compute U2Net subject mask + Sobel edge mask on every new photo.
    // Runs at IO priority (background) — no user action required to trigger.
    LaunchedEffect(bitmap) {
        val src = bitmap ?: return@LaunchedEffect
        if (aiMaskSourceBitmap === src) return@LaunchedEffect
        try {
            isComputingMask = true
            val mask = withContext(Dispatchers.IO) { aiLutProcessor.computeMask(src) }
            val edge = withContext(Dispatchers.Default) { aiLutProcessor.computeEdgeMask(src) }
            aiMask = mask
            sharpEdgeMask = edge
            aiMaskSourceBitmap = src
        } finally {
            isComputingMask = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(250)),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            bitmap ?: return@Surface

            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val actionsStorage = remember { ActionsStorage(context) }

            // ── Actions state ─────────────────────────────────────────────────
            // Action cards: newest at index 0, Original always at last position
            var actionCards by remember(visible) { mutableStateOf(listOf<ActionCard>(ActionCard.Original)) }
            var pendingTab by remember { mutableStateOf<FilterTab?>(null) }
            var cardCounters by remember { mutableStateOf(mapOf<String, Int>()) }
            var isApplyingAll by remember { mutableStateOf(false) }
            var savedActionsSets by remember { mutableStateOf<List<ActionsSet>>(emptyList()) }

            // Tone curves tab local state
            var toneCurvesEditorState by remember { mutableStateOf(ImageCurvesEditorState.Default) }

            // compositeBase: result of applying all committed visible cards to original bitmap
            var compositeBase by remember(bitmap) { mutableStateOf<Bitmap?>(null) }

            LaunchedEffect(visible) {
                if (visible) {
                    savedActionsSets = withContext(Dispatchers.IO) { actionsStorage.loadAllActionsSets() }
                }
            }

            // ── LUT state ─────────────────────────────────────────────────────
            var activeLutSelection by remember { mutableStateOf<LutSelection?>(null) }

            val activeLutFilter = remember(activeLutSelection) {
                activeLutSelection?.let {
                    UiCubeLutFilter(it.strength to FileModel(it.path))
                }
            }

            // ── Blur selection ────────────────────────────────────────────────
            var activeBlurSelection by remember { mutableStateOf<BlurSelection?>(null) }
            val activeBlurFilter = remember(activeBlurSelection) { activeBlurSelection?.filter }

            // ── Details selection ─────────────────────────────────────────────
            var activeDetailsSelection by remember { mutableStateOf<DetailsSelection?>(null) }
            var detailsPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }

            // ── Vignette selection ────────────────────────────────────────────
            var activeVignetteSelection by remember { mutableStateOf<VignetteSelection?>(null) }
            var vignettePreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }

            // ── Linear Gradient selection ─────────────────────────────────────
            var activeLinearGradientSelection by remember { mutableStateOf<LinearGradientSelection?>(null) }
            var linearGradientPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }

            // Preview only shows pending LUT filter (compositeBase is base via Picture model)
            val previewFilters = remember(activeLutFilter, pendingTab) {
                if (pendingTab == FilterTab.LUT) listOfNotNull(activeLutFilter)
                else emptyList()
            }

            val lutRepository = rememberLocalLutRepository()
            val lutCategoriesState = rememberLutCategories(lutRepository)
            var lutCategories by remember { mutableStateOf<List<LutCategory>>(emptyList()) }
            LaunchedEffect(lutCategoriesState.value) {
                lutCategories = lutCategoriesState.value
            }

            // ── compositeBase LaunchedEffect ──────────────────────────────────
            // Compute compositeBase: apply all committed visible action cards to original bitmap
            LaunchedEffect(actionCards, bitmap) {
                val orig = bitmap ?: return@LaunchedEffect
                var result = orig
                val orderedCards = actionCards
                    .filterNot { it is ActionCard.Original }
                    .filter { it.isVisible }
                    .reversed() // actionCards[0]=newest → reversed=oldest first for pipeline
                for (card in orderedCards) {
                    result = withContext(Dispatchers.Default) {
                        when (card) {
                            is ActionCard.LutCard -> {
                                var r = result
                                val lutFilter = UiCubeLutFilter(card.selection.strength to FileModel(card.selection.path))
                                r = transformWithFilter(r, lutFilter)
                                if (!card.selection.globalAdjustments.isEmpty) {
                                    r = aiLutProcessor.applyGlobalAdjustments(r, card.selection.globalAdjustments)
                                }
                                if (card.selection.edgeBlackClip > 0f) {
                                    r = aiLutProcessor.applyEdgeBlackClip(r, card.selection.edgeBlackClip)
                                }
                                r
                            }
                            is ActionCard.BlurCard -> transformWithFilter(result, card.selection.filter)
                            is ActionCard.DetailsCard -> detailsProcessor.apply(result, card.selection)
                            is ActionCard.LayersCard -> layersProcessor.apply(result, card.selection, null)
                            is ActionCard.VignetteCard -> {
                                val layersSel = LayersSelection(vignette = card.selection, linearGradient = LinearGradientSelection())
                                layersProcessor.apply(result, layersSel, null)
                            }
                            is ActionCard.LinearGradientCard -> {
                                val layersSel = LayersSelection(vignette = VignetteSelection(), linearGradient = card.selection)
                                layersProcessor.apply(result, layersSel, null)
                            }
                            is ActionCard.ToneCurvesCard -> ToneCurvesApplicator.apply(result, card.controlPoints)
                            is ActionCard.Original -> result
                        }
                    }
                }
                compositeBase = result
            }

            // Applies the active blur filter to the base bitmap whenever the filter or base changes.
            // Cached independently of AI params so edgeBlur/dofMode/bokehEdges slider changes
            // only re-run the cheaper AI overlay step, not the full blur filter.
            LaunchedEffect(activeBlurSelection?.filter, compositeBase, bitmap) {
                val base = compositeBase ?: bitmap
                val f    = activeBlurSelection?.filter
                blurredForAiPreview = if (base != null && f != null) {
                    withContext(Dispatchers.Default) { transformWithFilter(base, f) }
                } else null
            }

            // ── AI LUT preview adjustments LaunchedEffect ─────────────────────
            // Step 2 — apply global photo adjustments + fast pixel adjustments whenever any slider changes.
            LaunchedEffect(
                aiMask,
                sharpEdgeMask,
                activeLutSelection?.globalAdjustments,
                activeLutSelection?.subjectPop,
                activeLutSelection?.backgroundBrightness,
                activeLutSelection?.shadowBoost,
                activeLutSelection?.highlightBoost,
                activeLutSelection?.aiLutEnabled,
                activeLutSelection?.subjectTemperature,
                activeLutSelection?.backgroundTemperature,
                activeLutSelection?.subjectTint,
                activeLutSelection?.backgroundTint,
                activeLutSelection?.highlightTemperature,
                activeLutSelection?.highlightTint,
                activeLutSelection?.edgeBlackClip,
                stateBitmap,
            ) {
                val sel = activeLutSelection
                val globalAdj = sel?.globalAdjustments
                val hasGlobal = globalAdj != null && !globalAdj.isEmpty

                val noMasked = sel == null || !sel.aiLutEnabled ||
                    (sel.subjectPop == 0f && sel.backgroundBrightness == 0f &&
                     sel.subjectTemperature == 0f && sel.backgroundTemperature == 0f &&
                     sel.subjectTint == 0f && sel.backgroundTint == 0f)
                val noTone = sel == null ||
                    (sel.shadowBoost == 0f && sel.highlightBoost == 0f &&
                     sel.highlightTemperature == 0f && sel.highlightTint == 0f)
                val hasEdgeClip = sel != null && (sel.edgeBlackClip > 0f)
                if (!hasGlobal && noMasked && noTone && !hasEdgeClip) {
                    aiPreviewBitmap = null
                    return@LaunchedEffect
                }

                // stateBitmap is the LUT-rendered bitmap (Picture's onSuccess result).
                // Using it as the base ensures AI adjustments stack on top of the LUT,
                // instead of on the raw compositeBase which has no LUT color applied.
                val base = stateBitmap ?: compositeBase ?: bitmap ?: return@LaunchedEffect

                // Step 2a: global adjustments + clarity (no mask needed)
                var result: Bitmap = if (hasGlobal) {
                    withContext(Dispatchers.Default) {
                        aiLutProcessor.applyGlobalAdjustments(base, globalAdj!!)
                    }
                } else base
                if (hasEdgeClip) {
                    result = withContext(Dispatchers.Default) {
                        aiLutProcessor.applyEdgeBlackClip(result, sel!!.edgeBlackClip)
                    }
                }

                // Step 2b: AI LUT masked adjustments (subject/bg/tone)
                val needsMasked = !noMasked || !noTone
                if (needsMasked) {
                    val mask = if (!noMasked) (aiMask ?: return@LaunchedEffect) else null
                    result = withContext(Dispatchers.Default) {
                        aiLutProcessor.applyAdjustments(
                            bitmap = result,
                            mask = mask,
                            subjectPop = sel?.subjectPop ?: 0f,
                            backgroundBrightness = sel?.backgroundBrightness ?: 0f,
                            shadowBoost = sel?.shadowBoost ?: 0f,
                            highlightBoost = sel?.highlightBoost ?: 0f,
                            subjectTemperature = sel?.subjectTemperature ?: 0f,
                            backgroundTemperature = sel?.backgroundTemperature ?: 0f,
                            subjectTint = sel?.subjectTint ?: 0f,
                            backgroundTint = sel?.backgroundTint ?: 0f,
                            highlightTemperature = sel?.highlightTemperature ?: 0f,
                            highlightTint = sel?.highlightTint ?: 0f,
                        )
                    }
                }

                aiPreviewBitmap = result
            }

            // Step 3 — apply bokeh shaping and/or AI subject overlay whenever blur params change.
            LaunchedEffect(
                aiMask,
                activeBlurSelection?.aiEnabled,
                activeBlurSelection?.edgeBlur,
                activeBlurSelection?.bokehEdges,
                activeBlurSelection?.dofMode,
                blurredForAiPreview,
            ) {
                val blurSel   = activeBlurSelection
                val bokehEdges = blurSel?.bokehEdges ?: 0
                val aiEnabled  = blurSel?.aiEnabled ?: false
                if (blurSel == null || (!aiEnabled && bokehEdges == 0)) {
                    aiBlurPreviewBitmap = null
                    return@LaunchedEffect
                }
                // Use pre-computed blurred bitmap when available; fall back to unblurred base.
                val blurred    = blurredForAiPreview ?: compositeBase ?: bitmap ?: return@LaunchedEffect
                val origBitmap = compositeBase ?: bitmap ?: return@LaunchedEffect
                val dofMode    = blurSel.dofMode

                aiBlurPreviewBitmap = withContext(Dispatchers.Default) {
                    // 1. Shape bokeh highlights (ScatterCoC handles shaping internally).
                    val shaped = if (bokehEdges > 0 && dofMode != DofMode.ScatterCoC)
                        blurAiProcessor.applyBokehShape(blurred, bokehEdges) else blurred

                    // 2. Composite using the chosen DoF algorithm.
                    if (aiEnabled) {
                        val origScaled = if (origBitmap.width == blurred.width && origBitmap.height == blurred.height)
                            origBitmap
                        else Bitmap.createScaledBitmap(origBitmap, blurred.width, blurred.height, true)
                        val scaledAiMask = aiMask?.let { mask ->
                            if (origBitmap.width == blurred.width && origBitmap.height == blurred.height) mask
                            else blurAiProcessor.resizeMask(mask, origBitmap.width, origBitmap.height, blurred.width, blurred.height)
                        }
                        when (dofMode) {
                            DofMode.Standard   -> blurAiProcessor.applyBlurMask(origScaled, shaped, scaledAiMask, blurSel.edgeBlur)
                            DofMode.Guided     -> blurAiProcessor.applyGuidedDof(origScaled, shaped, scaledAiMask, blurSel.edgeBlur)
                            DofMode.Bilateral  -> blurAiProcessor.applyBilateralDof(origScaled, shaped, scaledAiMask, blurSel.edgeBlur)
                            DofMode.ScatterCoC -> blurAiProcessor.applyScatterCocDof(origScaled, shaped, scaledAiMask, blurSel.edgeBlur, bokehEdges)
                        }
                    } else {
                        shaped
                    }
                }
            }

            // Details — recompute preview whenever any slider changes or the base bitmap/overlays update.
            LaunchedEffect(
                activeDetailsSelection?.smartSharpness,
                activeDetailsSelection?.luminanceNR,
                activeDetailsSelection?.colorNR,
                activeDetailsSelection?.filmGrain,
                activeDetailsSelection?.filmGrainSize,
                activeDetailsSelection?.filmGrainUniformity,
                activeDetailsSelection?.filmGrainWashOut,
                stateBitmap,
                aiPreviewBitmap,
                aiBlurPreviewBitmap,
            ) {
                val sel = activeDetailsSelection
                if (sel == null || sel.isEmpty) {
                    detailsPreviewBitmap = null
                    return@LaunchedEffect
                }
                val base = aiBlurPreviewBitmap ?: aiPreviewBitmap ?: compositeBase ?: bitmap ?: return@LaunchedEffect
                detailsPreviewBitmap = detailsProcessor.apply(base, sel)
            }

            // Vignette — recompute preview whenever vignette params change or preceding stages update.
            LaunchedEffect(
                activeVignetteSelection,
                stateBitmap,
                aiPreviewBitmap,
                aiBlurPreviewBitmap,
                detailsPreviewBitmap,
                aiMask,
            ) {
                val sel = activeVignetteSelection
                if (sel == null || sel.isEmpty) {
                    vignettePreviewBitmap = null
                    return@LaunchedEffect
                }
                val base = detailsPreviewBitmap ?: aiBlurPreviewBitmap ?: aiPreviewBitmap ?: compositeBase ?: bitmap ?: return@LaunchedEffect
                val layersSel = LayersSelection(vignette = sel, linearGradient = LinearGradientSelection())
                vignettePreviewBitmap = layersProcessor.apply(base, layersSel, aiMask)
            }

            // Linear Gradient — recompute preview whenever linear gradient params change or preceding stages update.
            LaunchedEffect(
                activeLinearGradientSelection,
                stateBitmap,
                aiPreviewBitmap,
                aiBlurPreviewBitmap,
                detailsPreviewBitmap,
                vignettePreviewBitmap,
                aiMask,
            ) {
                val sel = activeLinearGradientSelection
                if (sel == null || sel.isEmpty) {
                    linearGradientPreviewBitmap = null
                    return@LaunchedEffect
                }
                val base = vignettePreviewBitmap ?: detailsPreviewBitmap ?: aiBlurPreviewBitmap ?: aiPreviewBitmap ?: compositeBase ?: bitmap ?: return@LaunchedEffect
                val layersSel = LayersSelection(vignette = VignetteSelection(), linearGradient = sel)
                linearGradientPreviewBitmap = layersProcessor.apply(base, layersSel, aiMask)
            }

            val tabs = FilterTab.entries
            val pagerState = rememberPagerState(pageCount = { tabs.size })
            val direction = LocalLayoutDirection.current

            // ── Helper: toggle visibility on a card ───────────────────────────
            fun toggleVisibility(card: ActionCard): ActionCard = when (card) {
                is ActionCard.LutCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.BlurCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.DetailsCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.LayersCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.VignetteCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.LinearGradientCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.ToneCurvesCard -> card.copy(isVisible = !card.isVisible)
                is ActionCard.Original -> card
            }

            // ── Helper: generate card display name ────────────────────────────
            fun nextCardName(type: String): String {
                val n = (cardCounters[type] ?: 0) + 1
                cardCounters = cardCounters + (type to n)
                return "$type ${n.toString().padStart(2, '0')}"
            }

            // ── Apply pending tab: commit current tab state as an ActionCard ──
            fun applyPendingTab() {
                val tab = pendingTab ?: return
                val card: ActionCard? = when (tab) {
                    FilterTab.LUT -> activeLutSelection?.let { sel ->
                        ActionCard.LutCard(displayName = nextCardName("LUT"), selection = sel)
                    }
                    FilterTab.ToneCurves -> if (!toneCurvesEditorState.isDefault()) {
                        ActionCard.ToneCurvesCard(
                            displayName = nextCardName("Tone Curves"),
                            controlPoints = toneCurvesEditorState.controlPoints,
                        )
                    } else null
                    FilterTab.Blur -> activeBlurSelection?.let { sel ->
                        ActionCard.BlurCard(displayName = nextCardName("Blur"), selection = sel)
                    }
                    FilterTab.Details -> activeDetailsSelection?.takeIf { !it.isEmpty }?.let { sel ->
                        ActionCard.DetailsCard(displayName = nextCardName("Details"), selection = sel)
                    }
                    FilterTab.Vignette -> activeVignetteSelection?.takeIf { !it.isEmpty }?.let { sel ->
                        ActionCard.VignetteCard(displayName = nextCardName("Vignette"), selection = sel)
                    }
                    FilterTab.LinearGradient -> activeLinearGradientSelection?.takeIf { !it.isEmpty }?.let { sel ->
                        ActionCard.LinearGradientCard(displayName = nextCardName("Linear Gradient"), selection = sel)
                    }
                    FilterTab.Actions -> null
                }
                if (card != null) {
                    // Add new card at index 0 (newest first), Original stays at last
                    val originalCard = actionCards.last()
                    actionCards = listOf(card) + actionCards.dropLast(1) + listOf(originalCard)
                }
                // Reset pending state
                pendingTab = null
                activeLutSelection = null
                activeBlurSelection = null
                activeDetailsSelection = null
                activeVignetteSelection = null
                activeLinearGradientSelection = null
                toneCurvesEditorState = ImageCurvesEditorState.Default
            }

            // ── Cancel/Reset pending tab: revert current tab state and unlock ──
            fun resetPendingTab() {
                val tab = pendingTab ?: return
                when (tab) {
                    FilterTab.LUT -> activeLutSelection = null
                    FilterTab.ToneCurves -> toneCurvesEditorState = ImageCurvesEditorState.Default
                    FilterTab.Blur -> { activeBlurSelection = null; blurredForAiPreview = null }
                    FilterTab.Details -> activeDetailsSelection = null
                    FilterTab.Vignette -> activeVignetteSelection = null
                    FilterTab.LinearGradient -> activeLinearGradientSelection = null
                    FilterTab.Actions -> {}
                }
                pendingTab = null
            }

            // ── Apply all cards at full resolution and dismiss ────────────────
            fun triggerApplyAll() {
                scope.launch {
                    isApplyingAll = true
                    val origBitmap = fullResBitmap ?: bitmap ?: run { isApplyingAll = false; return@launch }

                    val previewSrc = aiMaskSourceBitmap
                    val previewW = previewSrc?.width ?: origBitmap.width
                    val previewH = previewSrc?.height ?: origBitmap.height
                    val needsMaskScale = previewW != origBitmap.width || previewH != origBitmap.height

                    fun FloatArray.upscaleToOrig(): FloatArray =
                        if (needsMaskScale) blurAiProcessor.resizeMask(this, previewW, previewH, origBitmap.width, origBitmap.height)
                        else this

                    var cachedMask: FloatArray? = null

                    suspend fun getOrComputeMask(): FloatArray? {
                        return cachedMask ?: run {
                            val m = aiMask?.upscaleToOrig()
                                ?: withContext(Dispatchers.IO) { aiLutProcessor.computeMask(origBitmap) }
                            if (m != null) cachedMask = m
                            m
                        }
                    }

                    var result = origBitmap
                    val orderedCards = actionCards
                        .filterNot { it is ActionCard.Original }
                        .filter { it.isVisible }
                        .reversed()

                    for (card in orderedCards) {
                        result = when (card) {
                            is ActionCard.LutCard -> {
                                var r = result
                                val lutFilter = UiCubeLutFilter(card.selection.strength to FileModel(card.selection.path))
                                r = withContext(Dispatchers.Default) { transformWithFilter(r, lutFilter) }
                                if (!card.selection.globalAdjustments.isEmpty) {
                                    r = withContext(Dispatchers.Default) {
                                        aiLutProcessor.applyGlobalAdjustments(r, card.selection.globalAdjustments)
                                    }
                                }
                                if (card.selection.edgeBlackClip > 0f) {
                                    r = withContext(Dispatchers.Default) {
                                        aiLutProcessor.applyEdgeBlackClip(r, card.selection.edgeBlackClip)
                                    }
                                }
                                if (card.selection.aiLutEnabled) {
                                    val mask = getOrComputeMask()
                                    r = withContext(Dispatchers.Default) {
                                        aiLutProcessor.applyAdjustments(
                                            r, mask,
                                            card.selection.subjectPop, card.selection.backgroundBrightness,
                                            card.selection.shadowBoost, card.selection.highlightBoost,
                                            card.selection.subjectTemperature, card.selection.backgroundTemperature,
                                            card.selection.subjectTint, card.selection.backgroundTint,
                                            card.selection.highlightTemperature, card.selection.highlightTint,
                                        )
                                    }

                                }
                                r
                            }
                            is ActionCard.BlurCard -> {
                                val dofMode = card.selection.dofMode
                                var r = withContext(Dispatchers.Default) { transformWithFilter(result, card.selection.filter) }
                                // ScatterCoC applies its own aperture-shaped scatter; others use fixed N-gon.
                                if (card.selection.bokehEdges > 0 && dofMode != DofMode.ScatterCoC) {
                                    r = withContext(Dispatchers.Default) { blurAiProcessor.applyBokehShape(r, card.selection.bokehEdges) }
                                }
                                if (card.selection.aiEnabled) {
                                    val mask = getOrComputeMask()
                                    r = withContext(Dispatchers.Default) {
                                        when (dofMode) {
                                            DofMode.Standard   -> blurAiProcessor.applyBlurMask(result, r, mask, card.selection.edgeBlur)
                                            DofMode.Guided     -> blurAiProcessor.applyGuidedDof(result, r, mask, card.selection.edgeBlur)
                                            DofMode.Bilateral  -> blurAiProcessor.applyBilateralDof(result, r, mask, card.selection.edgeBlur)
                                            DofMode.ScatterCoC -> blurAiProcessor.applyScatterCocDof(result, r, mask, card.selection.edgeBlur, card.selection.bokehEdges)
                                        }
                                    }
                                }
                                r
                            }
                            is ActionCard.DetailsCard -> withContext(Dispatchers.Default) { detailsProcessor.apply(result, card.selection) }
                            is ActionCard.LayersCard -> {
                                val mask = getOrComputeMask().takeIf { card.selection.vignette.includeSubject }
                                withContext(Dispatchers.Default) { layersProcessor.apply(result, card.selection, mask) }
                            }
                            is ActionCard.VignetteCard -> {
                                val mask = getOrComputeMask().takeIf { !card.selection.includeSubject }
                                val layersSel = LayersSelection(vignette = card.selection, linearGradient = LinearGradientSelection())
                                withContext(Dispatchers.Default) { layersProcessor.apply(result, layersSel, mask) }
                            }
                            is ActionCard.LinearGradientCard -> {
                                val mask = getOrComputeMask()
                                val layersSel = LayersSelection(vignette = VignetteSelection(), linearGradient = card.selection)
                                withContext(Dispatchers.Default) { layersProcessor.apply(result, layersSel, mask) }
                            }
                            is ActionCard.ToneCurvesCard -> withContext(Dispatchers.Default) { ToneCurvesApplicator.apply(result, card.controlPoints) }
                            is ActionCard.Original -> result
                        }
                    }

                    onGetBitmap(result)
                    isApplyingAll = false
                    onDismiss()
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {

                // ── Top App Bar ──────────────────────────────────────────────
                EnhancedTopAppBar(
                    type = EnhancedTopAppBarType.Center,
                    navigationIcon = {
                        EnhancedIconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    },
                    actions = {
                        val hasChanges = actionCards.size > 1 || pendingTab != null
                        AnimatedVisibility(visible = hasChanges) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ShowOriginalButton(
                                    canShow = true,
                                    onStateChange = {
                                        showOriginal = it
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                if (isApplyingAll) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .padding(6.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    },
                    title = {
                        Text(
                            text = stringResource(R.string.filter),
                            modifier = Modifier.marquee(),
                        )
                    },
                )

                // ── Local helpers: preview and controls panels ───────────────

                @Composable
                fun PreviewBox(modifier: Modifier) {
                    val activeOverlayBitmap = remember(
                        aiPreviewBitmap,
                        aiBlurPreviewBitmap,
                        detailsPreviewBitmap,
                        vignettePreviewBitmap,
                        linearGradientPreviewBitmap
                    ) {
                        linearGradientPreviewBitmap ?: vignettePreviewBitmap ?: detailsPreviewBitmap ?: aiBlurPreviewBitmap ?: aiPreviewBitmap
                    }
                    Box(
                        modifier = modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .transparencyChecker()
                            .clipToBounds(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val zoomState = rememberZoomState()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .zoomable(zoomState)
                        ) {
                            if (showOriginal) {
                                bitmap?.let { origBmp ->
                                    Image(
                                        bitmap = origBmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(
                                                start = WindowInsets.displayCutout
                                                    .asPaddingValues()
                                                    .calculateStartPadding(direction),
                                            ),
                                    )
                                }
                            } else {
                                Picture(
                                    model = compositeBase ?: bitmap,
                                    shape = RectangleShape,
                                    transformations = remember(previewFilters) {
                                        derivedStateOf {
                                            onRequestMappingFilters(previewFilters).map { it.toCoil() }
                                        }
                                    }.value,
                                    onSuccess = {
                                        stateBitmap = it.result.image.toBitmap()
                                    },
                                    onError = {
                                        // Filter pipeline failed (e.g. missing LUT file) — drop the
                                        // broken LUT selection so the preview recovers immediately.
                                        if (activeLutSelection != null) {
                                            activeLutSelection = null
                                        }
                                    },
                                    showTransparencyChecker = false,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            start = WindowInsets.displayCutout
                                                .asPaddingValues()
                                                .calculateStartPadding(direction),
                                        ),
                                )

                                activeOverlayBitmap?.let { overlayBmp ->
                                    Image(
                                        bitmap = overlayBmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        // Spinner while U2Net mask is computing
                        if (isComputingMask) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                @Composable
                fun TabRowContent() {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 0.dp,
                        divider = {},
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            val isThisTabLocked = pendingTab != null && pendingTab != tab && tab != FilterTab.Actions
                            Tab(
                                selected = selected,
                                onClick = {
                                    if (!isThisTabLocked) {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                },
                                modifier = if (isThisTabLocked) Modifier.alpha(0.38f) else Modifier,
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            FilterTab.Actions -> Icons.Rounded.Stacks
                                            FilterTab.LUT -> Icons.Rounded.Cube
                                            FilterTab.ToneCurves -> Icons.Rounded.Tonality
                                            FilterTab.Blur -> Icons.Rounded.BlurCircular
                                            FilterTab.Details -> Icons.Rounded.Tune
                                            FilterTab.Vignette -> Icons.Rounded.CenterFocusStrong
                                            FilterTab.LinearGradient -> Icons.Rounded.Gradient
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                text = {
                                    Text(
                                        text = stringResource(
                                            when (tab) {
                                                FilterTab.Actions -> R.string.actions
                                                FilterTab.LUT -> R.string.lut
                                                FilterTab.ToneCurves -> R.string.tone_curves
                                                FilterTab.Blur -> R.string.blur
                                                FilterTab.Details -> R.string.details
                                                FilterTab.Vignette -> R.string.vignette
                                                FilterTab.LinearGradient -> R.string.linear_gradient
                                            }
                                        )
                                    )
                                },
                            )
                        }
                    }
                }

                @Composable
                fun PagerContent(pagerModifier: Modifier) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = pagerModifier,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = pendingTab == null,
                    ) { page ->
                        when (tabs[page]) {
                            FilterTab.Actions -> {
                                ActionsTabContent(
                                    actionCards = actionCards,
                                    savedActionsSets = savedActionsSets,
                                    onEyeToggle = { id ->
                                        actionCards = actionCards.map { if (it.id == id) toggleVisibility(it) else it }
                                    },
                                    onDelete = { id ->
                                        actionCards = actionCards.filter { it.id != id }
                                    },
                                    onApplyAll = {
                                        triggerApplyAll()
                                    },
                                    onSaveActions = { name ->
                                        scope.launch(Dispatchers.IO) {
                                            val set = ActionsSet(
                                                name = name,
                                                cards = actionCards.filterNot { it is ActionCard.Original },
                                            )
                                            actionsStorage.saveActionsSet(set)
                                            savedActionsSets = actionsStorage.loadAllActionsSets()
                                        }
                                    },
                                    onDeleteActionsSet = { name ->
                                        scope.launch(Dispatchers.IO) {
                                            actionsStorage.deleteActionsSet(name)
                                            savedActionsSets = actionsStorage.loadAllActionsSets()
                                        }
                                    },
                                    isApplyingAll = isApplyingAll,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            FilterTab.LUT -> {
                                LutTabContent(
                                    repository = lutRepository,
                                    categories = lutCategories,
                                    onCategoriesChanged = {
                                        scope.launch {
                                            lutCategories = lutRepository.getCategories()
                                        }
                                    },
                                    onLutSelectionChanged = { sel ->
                                        activeLutSelection = sel
                                        if (pendingTab == null && sel != null) {
                                            pendingTab = FilterTab.LUT
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    aiMaskAvailable = aiMask != null,
                                    debugOriginalBitmap = bitmap,
                                    debugPreviewBitmap = aiPreviewBitmap ?: stateBitmap,
                                    debugSubjectMask = aiMask,
                                    debugEdgeMask = sharpEdgeMask,
                                    debugAiProcessor = aiLutProcessor,
                                )
                            }

                            FilterTab.ToneCurves -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ImageCurvesEditor(
                                        bitmap = compositeBase ?: bitmap,
                                        state = toneCurvesEditorState,
                                        curvesSelectionText = {
                                            Text(
                                                text = when (it) {
                                                    0 -> stringResource(R.string.all)
                                                    1 -> stringResource(R.string.color_red)
                                                    2 -> stringResource(R.string.color_green)
                                                    3 -> stringResource(R.string.color_blue)
                                                    else -> ""
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                        placeControlsAtTheEnd = !useScaffold,
                                        imageObtainingTrigger = false,
                                        onImageObtained = { /* not used */ },
                                        showOriginal = false,
                                        onStateChange = { newState ->
                                            toneCurvesEditorState = newState
                                            if (pendingTab == null && !newState.isDefault()) {
                                                pendingTab = FilterTab.ToneCurves
                                            }
                                        },
                                    )
                                }
                            }

                            FilterTab.Blur -> {
                                BlurTabContent(
                                    filters = UiFilter.Group.Blur.filters(canAddTemplates = false),
                                    onBlurSelectionChanged = { sel ->
                                        activeBlurSelection = sel
                                        if (pendingTab == null && sel != null) {
                                            pendingTab = FilterTab.Blur
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            FilterTab.Details -> {
                                DetailsTabContent(
                                    onSelectionChanged = { sel ->
                                        activeDetailsSelection = sel
                                        if (pendingTab == null && sel != null && !sel.isEmpty) {
                                            pendingTab = FilterTab.Details
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            FilterTab.Vignette -> {
                                VignetteTabContent(
                                    onSelectionChanged = { sel ->
                                        activeVignetteSelection = sel
                                        if (pendingTab == null && sel != null && !sel.isEmpty) {
                                            pendingTab = FilterTab.Vignette
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            FilterTab.LinearGradient -> {
                                LinearGradientTabContent(
                                    onSelectionChanged = { sel ->
                                        activeLinearGradientSelection = sel
                                        if (pendingTab == null && sel != null && !sel.isEmpty) {
                                            pendingTab = FilterTab.LinearGradient
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                @Composable
                fun ApplyButtonContent() {
                    AnimatedVisibility(visible = pendingTab != null && pendingTab != FilterTab.Actions) {
                        val pendingTabName = when (pendingTab) {
                            FilterTab.LUT -> stringResource(R.string.lut)
                            FilterTab.ToneCurves -> stringResource(R.string.tone_curves)
                            FilterTab.Blur -> stringResource(R.string.blur)
                            FilterTab.Details -> stringResource(R.string.details)
                            FilterTab.Vignette -> stringResource(R.string.vignette)
                            FilterTab.LinearGradient -> stringResource(R.string.linear_gradient)
                            else -> ""
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EnhancedIconButton(
                                onClick = { resetPendingTab() },
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Cancel"
                                )
                            }

                            EnhancedButton(
                                onClick = {
                                    applyPendingTab()
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Text("${stringResource(R.string.apply_tab)} $pendingTabName")
                            }
                        }
                    }
                }

                if (useScaffold) {
                    // ── Portrait: 3:2 preview stacked above controls ─────────────
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val previewHeight = maxWidth * (2f / 3f)
                        PreviewBox(modifier = Modifier.fillMaxWidth().height(previewHeight))
                    }
                    TabRowContent()
                    PagerContent(pagerModifier = Modifier.weight(1f))
                    ApplyButtonContent()
                } else {
                    // ── Landscape: preview left, controls right ───────────────────
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PreviewBox(modifier = Modifier.weight(1f).fillMaxHeight())
                        Column(modifier = Modifier.weight(1f)) {
                            TabRowContent()
                            PagerContent(pagerModifier = Modifier.weight(1f))
                            ApplyButtonContent()
                        }
                    }
                }
            }
        }
    }
}
