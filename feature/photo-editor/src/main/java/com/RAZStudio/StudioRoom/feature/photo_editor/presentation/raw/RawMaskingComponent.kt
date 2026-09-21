/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.graphics.Bitmap
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskBrushMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3MulticlassMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3SegmentationMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3CityscapesMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3DepthMap
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3DeepLabMasks
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Coordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

interface RawMaskingComponent {
    val segmentationMasks: StateFlow<RawSegmentationMasks?>
    val segmentationMasksV3: StateFlow<RawV3SegmentationMasks?>
    val multiclassMasks: StateFlow<RawV3MulticlassMasks?>
    val multiclassLoading: StateFlow<Boolean>
    val segmentationRunning: StateFlow<Boolean>
    val faceMask: StateFlow<FloatArray?>
    val cityscapesMasks: StateFlow<RawV3CityscapesMasks?>
    val depthMap: StateFlow<RawV3DepthMap?>
    val cityscapesLoading: StateFlow<Boolean>
    val deepLabMasks: StateFlow<RawV3DeepLabMasks?>

    val maskBitmap: StateFlow<Bitmap?>
    val maskDirty: StateFlow<Int>
    val maskLayerBitmaps: StateFlow<List<Bitmap?>>

    val brushSize: StateFlow<Float>
    val brushIntensity: StateFlow<Float>
    val brushFeather: StateFlow<Float>
    val brushMode: StateFlow<MaskBrushMode>
    val isMaskModeActive: StateFlow<Boolean>

    val includedMaskClasses: StateFlow<Set<MaskClass>>
    val primaryMaskClass: StateFlow<MaskClass?>
    val sharpSpread: StateFlow<Float>
    val primaryIsLuma: StateFlow<Boolean>
    val primaryIsChroma: StateFlow<Boolean>
    val chromaSubtractMode: StateFlow<Boolean>
    val maskColorSamples: StateFlow<List<Int>>
    val maskColorRange: StateFlow<Float>
    val maskColorFeather: StateFlow<Float>
    val maskColorTolerance: StateFlow<Float>

    // ─── Operational mask graph (lightweight undo/redo) ─────────────
    // Nodes are descriptors (brush paths, model planes, colour recipes) —
    // never full-res snapshots — so history costs KBs per step. The
    // composite bitmap is re-evaluated from the graph on every transition
    // and published through [updateMask]; see MaskGraph.kt.
    val maskNodes: StateFlow<List<MaskNode>>
    fun pushMaskNode(node: MaskNode)
    fun undoMaskNode(): Boolean
    fun redoMaskNode(): Boolean
    fun setMaskNodeEnabled(id: String, enabled: Boolean)
    fun clearMaskGraph()
    fun canUndoMaskNode(): Boolean
    fun canRedoMaskNode(): Boolean
    /** Owner (the editor content) supplies the rasterizers + publish path;
     *  invoked on every graph transition with the new node list. */
    fun setMaskGraphRebuildListener(listener: ((List<MaskNode>) -> Unit)?)

    fun updateMask(bitmap: Bitmap?)
    fun setMaskLayers(layers: List<RawAction>)

    /**
     * Restore the complete state of a mask instance (nodes + class metadata).
     * Used when switching back to an existing mask layer.
     */
    fun restoreMaskState(
        nodes: List<MaskNode>,
        primary: MaskClass?,
        included: Set<MaskClass>,
        isLuma: Boolean,
        isChroma: Boolean
    )

    /**
     * Publish the committed brush-mask layer bitmaps (up to 4, bottommost
     * first) and mirror the topmost into the single-mask flow, bumping
     * [maskDirty] so the preview re-uploads. Empty list clears both.
     */
    fun publishMaskLayers(bitmaps: List<Bitmap?>)
    fun setBrushSize(size: Float)
    fun setBrushIntensity(intensity: Float)
    fun setBrushFeather(feather: Float)
    fun setBrushMode(mode: MaskBrushMode)
    fun setIsMaskModeActive(active: Boolean)
    fun setIncludedMaskClasses(classes: Set<MaskClass>)
    fun setPrimaryMaskClass(maskClass: MaskClass?)
    fun setSharpSpread(spread: Float)
    fun setPrimaryIsLuma(active: Boolean)
    fun setPrimaryIsChroma(active: Boolean)
    fun setChromaSubtractMode(active: Boolean)
    fun setMaskColorSamples(samples: List<Int>)
    fun setMaskColorRange(range: Float)
    fun setMaskColorFeather(feather: Float)
    fun setMaskColorTolerance(tolerance: Float)
}

class RawMaskingComponentImpl(
    componentContext: ComponentContext,
    private val v3: RawV3Coordinator,
    private val scope: CoroutineScope
) : RawMaskingComponent, ComponentContext by componentContext {

    override val segmentationMasks: StateFlow<RawSegmentationMasks?> = v3.segmentationMasks
        .combine(MutableStateFlow(Unit)) { v3Masks, _ ->
            v3Masks?.let { adaptV3SegmentationMasks(it) }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val segmentationMasksV3 = v3.segmentationMasks
    override val multiclassMasks = v3.multiclassMasks
    override val multiclassLoading = v3.multiclassLoading
    override val segmentationRunning = v3.segmentationRunning
    override val faceMask = v3.faceMask
    override val cityscapesMasks = v3.cityscapesMasks
    override val depthMap = v3.depthMap
    override val cityscapesLoading = v3.cityscapesLoading
    override val deepLabMasks = v3.deepLabMasks

    private val _maskBitmap = MutableStateFlow<Bitmap?>(null)
    override val maskBitmap = _maskBitmap.asStateFlow()

    private val _maskDirty = MutableStateFlow(0)
    override val maskDirty = _maskDirty.asStateFlow()

    private val _maskLayerBitmaps = MutableStateFlow<List<Bitmap?>>(emptyList())
    override val maskLayerBitmaps = _maskLayerBitmaps.asStateFlow()

    private val _brushSize = MutableStateFlow(30f)
    override val brushSize = _brushSize.asStateFlow()

    private val _brushIntensity = MutableStateFlow(0.8f)
    override val brushIntensity = _brushIntensity.asStateFlow()

    private val _brushFeather = MutableStateFlow(0.3f)
    override val brushFeather = _brushFeather.asStateFlow()

    private val _brushMode = MutableStateFlow(MaskBrushMode.Draw)
    override val brushMode = _brushMode.asStateFlow()

    private val _isMaskModeActive = MutableStateFlow(false)
    override val isMaskModeActive = _isMaskModeActive.asStateFlow()

    private val _includedMaskClasses = MutableStateFlow(emptySet<MaskClass>())
    override val includedMaskClasses = _includedMaskClasses.asStateFlow()

    private val _primaryMaskClass = MutableStateFlow<MaskClass?>(null)
    override val primaryMaskClass = _primaryMaskClass.asStateFlow()

    private val _sharpSpread = MutableStateFlow(0f)
    override val sharpSpread = _sharpSpread.asStateFlow()

    private val _primaryIsLuma = MutableStateFlow(false)
    override val primaryIsLuma = _primaryIsLuma.asStateFlow()

    private val _primaryIsChroma = MutableStateFlow(false)
    override val primaryIsChroma = _primaryIsChroma.asStateFlow()

    private val _chromaSubtractMode = MutableStateFlow(false)
    override val chromaSubtractMode = _chromaSubtractMode.asStateFlow()

    private val _maskColorSamples = MutableStateFlow(emptyList<Int>())
    override val maskColorSamples = _maskColorSamples.asStateFlow()

    private val _maskColorRange = MutableStateFlow(60f)
    override val maskColorRange = _maskColorRange.asStateFlow()

    private val _maskColorFeather = MutableStateFlow(30f)
    override val maskColorFeather = _maskColorFeather.asStateFlow()

    private val _maskColorTolerance = MutableStateFlow(50f)
    override val maskColorTolerance = _maskColorTolerance.asStateFlow()

    // ─── Operational mask graph ─────────────────────────────────────
    private val _maskNodes = MutableStateFlow<List<MaskNode>>(emptyList())
    override val maskNodes = _maskNodes.asStateFlow()
    private var graphRebuildListener: ((List<MaskNode>) -> Unit)? = null
    private val history = MaskHistoryTracker { nodes ->
        _maskNodes.value = nodes
        graphRebuildListener?.invoke(nodes)
    }

    override fun pushMaskNode(node: MaskNode) = history.pushOperation(node)
    override fun undoMaskNode(): Boolean = history.undo()
    override fun redoMaskNode(): Boolean = history.redo()
    override fun setMaskNodeEnabled(id: String, enabled: Boolean) {
        history.replaceCurrent(history.currentNodes.map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        })
    }
    override fun clearMaskGraph() = history.reset()
    override fun canUndoMaskNode(): Boolean = history.canUndo()
    override fun canRedoMaskNode(): Boolean = history.canRedo()
    override fun setMaskGraphRebuildListener(listener: ((List<MaskNode>) -> Unit)?) {
        graphRebuildListener = listener
    }

    override fun updateMask(bitmap: Bitmap?) {
        _maskBitmap.value = bitmap
        _maskDirty.value++
    }

    override fun setMaskLayers(layers: List<RawAction>) {}

    override fun restoreMaskState(
        nodes: List<MaskNode>,
        primary: MaskClass?,
        included: Set<MaskClass>,
        isLuma: Boolean,
        isChroma: Boolean
    ) {
        android.util.Log.d("MaskDebug", "restoreMaskState: nodes=${nodes.size} primary=$primary included=${included.size}")
        _includedMaskClasses.value = included
        _primaryMaskClass.value = primary
        _primaryIsLuma.value = isLuma
        _primaryIsChroma.value = isChroma
        history.replaceCurrent(nodes)
    }

    override fun publishMaskLayers(bitmaps: List<Bitmap?>) {
        _maskLayerBitmaps.value = bitmaps
        _maskBitmap.value = bitmaps.lastOrNull()
        _maskDirty.value = _maskDirty.value + 1
    }

    override fun setBrushSize(size: Float) { _brushSize.value = size }
    override fun setBrushIntensity(intensity: Float) { _brushIntensity.value = intensity }
    override fun setBrushFeather(feather: Float) { _brushFeather.value = feather }
    override fun setBrushMode(mode: MaskBrushMode) { _brushMode.value = mode }
    override fun setIsMaskModeActive(active: Boolean) { _isMaskModeActive.value = active }
    override fun setIncludedMaskClasses(classes: Set<MaskClass>) { _includedMaskClasses.value = classes }
    override fun setPrimaryMaskClass(maskClass: MaskClass?) { _primaryMaskClass.value = maskClass }
    override fun setSharpSpread(spread: Float) { _sharpSpread.value = spread }
    override fun setPrimaryIsLuma(active: Boolean) { _primaryIsLuma.value = active }
    override fun setPrimaryIsChroma(active: Boolean) { _primaryIsChroma.value = active }
    override fun setChromaSubtractMode(active: Boolean) { _chromaSubtractMode.value = active }
    override fun setMaskColorSamples(samples: List<Int>) { _maskColorSamples.value = samples }
    override fun setMaskColorRange(range: Float) { _maskColorRange.value = range.coerceIn(0f, 100f) }
    override fun setMaskColorFeather(feather: Float) { _maskColorFeather.value = feather.coerceIn(0f, 100f) }
    override fun setMaskColorTolerance(tolerance: Float) { _maskColorTolerance.value = tolerance.coerceIn(0f, 100f) }

    private fun adaptV3SegmentationMasks(
        v3: RawV3SegmentationMasks
    ): RawSegmentationMasks = RawSegmentationMasks(
        subjectMask = v3.subjectMask,
        edgeMask = v3.edgeMask,
        refinedMask = v3.refinedMask,
        refinedWidth = v3.refinedWidth,
        refinedHeight = v3.refinedHeight,
    )
}
