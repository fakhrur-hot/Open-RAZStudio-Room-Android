/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.RAZStudio.StudioRoom.core.ui.theme.Elevation
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawEditorComponent
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawPipelineState
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskBrushMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks

@Composable
fun RawEditorPanel(
    modifier: Modifier,
    component: RawEditorComponent,
    uiState: RawPipelineState,
    previewBitmap: Bitmap?,
    gradedHistogram: IntArray?,
    deltaMacro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    actions: List<RawAction>,
    presets: List<com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage.Preset>,
    onApplyAction: (String, Int, UserMacro) -> Unit,
    onCancelAction: () -> Unit,
    onLoadAction: (RawAction) -> Unit,
    onRestoreAction: (Int, RawAction) -> Unit,
    onDeleteAction: (String, Boolean) -> Unit,
    onEyeToggleAction: (String) -> Unit,
    onToggleLockAction: (String) -> Unit,
    onSavePreset: (String) -> Boolean,
    onLoadPreset: (Int) -> Unit,
    onDeletePreset: (Int) -> Unit,
    onExportDebugMap: (Int) -> Unit = {},
    onExportToEditor: () -> Unit,
    onExportActions: () -> Unit,
    onImportActions: () -> Unit,
    onTabSelected: (Int) -> Unit,
    isVignetteCenterMode: Boolean,
    onVignetteCenterModeChange: (Boolean) -> Unit,
    isLensFlareMoveMode: Boolean = false,
    onLensFlareMoveModeChange: (Boolean) -> Unit = {},
    onUndoLastHeal: () -> Unit,
    onHealApply: () -> Unit,
    onHealCancel: () -> Unit,
    onSaveEditAsLut: (suspend (String) -> String?)?,
    onMaskModeActive: (Boolean) -> Unit,
    onShowMaskOverlayChange: (Boolean) -> Unit,
    onBrushModeChange: (MaskBrushMode) -> Unit,
    onSharpSpreadChange: (Float) -> Unit,
    onFillSharp: () -> Unit,
    onSegmentationNeeded: () -> Unit,
    subjectSegBusy: Boolean,
    subjectDetected: Boolean,
    imageLongSide: Int,
    onLightAuto: () -> Unit,
    onLightAutoOff: () -> Unit,
    onLightBasicAuto: () -> Unit,
    lightAutoEnabled: Boolean,
    lightAeActive: Boolean,
    onLightAeProtectionChange: (Float) -> Unit,
    isToneCurvesTab: Boolean,
    isMaskTab: Boolean,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    brushIntensity: Float,
    onBrushIntensity: (Float) -> Unit,
    brushFeather: Float,
    onBrushFeather: (Float) -> Unit,
    hasMask: Boolean,
    onClearMask: () -> Unit,
    onFillSubject: () -> Unit,
    onFillBackground: () -> Unit,
    onRemoveSubject: () -> Unit,
    onRemoveBackground: () -> Unit,
    onInvertMask: () -> Unit,
    onFillDetectedClass: (MaskClass) -> Unit,
    onRemoveDetectedClass: (MaskClass) -> Unit,
    primaryIsLuma: Boolean,
    primaryIsChroma: Boolean,
    onAddLuma: () -> Unit,
    onRemoveLuma: () -> Unit,
    onAddChroma: () -> Unit,
    onRemoveChroma: () -> Unit,
    onColorToleranceChange: (Float) -> Unit,
    onClearColorSamples: () -> Unit,
    isFullResReady: Boolean,
    isFullResProcessing: Boolean,
    /** Scene-adaptive auto-enhance toggle state + handler (Color tab). */
    sceneAutoEnhanceEnabled: Boolean = false,
    onSceneAutoEnhanceChange: (Boolean) -> Unit = {},
) {
    val isPreviewReady = uiState is RawPipelineState.PreviewReady ||
            uiState is RawPipelineState.FullResProcessing ||
            uiState is RawPipelineState.FullResReady

    if (!isPreviewReady) return

    val healRadiusPx by component.healing.healRadiusPx.collectAsState()
    val healActive by component.healing.healActive.collectAsState()
    val isHealing by component.healing.isHealing.collectAsState()
    val healCount by component.healing.healCount.collectAsState()
    val healDirty by component.healing.healDirty.collectAsState()

    val isMaskModeActive by component.masking.isMaskModeActive.collectAsState()
    val brushMode by component.masking.brushMode.collectAsState()
    val sharpSpread by component.masking.sharpSpread.collectAsState()
    val segmentationMasks by component.masking.segmentationMasks.collectAsState()
    val multiclassMasks by component.masking.multiclassMasks.collectAsState()
    val multiclassLoading by component.masking.multiclassLoading.collectAsState()
    val cityscapesMasks by component.masking.cityscapesMasks.collectAsState()
    val cityscapesLoading by component.masking.cityscapesLoading.collectAsState()

    val includedMaskClasses by component.masking.includedMaskClasses.collectAsState()
    val primaryMaskClass by component.masking.primaryMaskClass.collectAsState()
    val colorTolerance by component.masking.maskColorTolerance.collectAsState()
    val colorRange by component.masking.maskColorRange.collectAsState()
    val colorFeather by component.masking.maskColorFeather.collectAsState()
    val colorSamples by component.masking.maskColorSamples.collectAsState()
    val chromaSubtractMode by component.masking.chromaSubtractMode.collectAsState()

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPanelControlsEnabled provides true,
    ) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevation.panel,
            shadowElevation = Elevation.panel,
        ) {
            RawAdjustmentPanel(
                macro = deltaMacro,
                onMacroChange = onMacroChange,
                previewBitmap = previewBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                gradedHistogram = gradedHistogram,
                actions = actions,
                onApplyAction = onApplyAction,
                onCancelAction = onCancelAction,
                composeTabMacro = { tab -> component.composeTabMacro(tab) },
                onReplaceTabCards = { tab, cards -> component.replaceTabCards(tab, cards) },
                onLoadAction = onLoadAction,
                onRestoreAction = onRestoreAction,
                onDeleteAction = onDeleteAction,
                onEyeToggleAction = onEyeToggleAction,
                onToggleLockAction = onToggleLockAction,
                presets = presets,
                onSavePreset = onSavePreset,
                onLoadPreset = onLoadPreset,
                onDeletePreset = onDeletePreset,
                onExportDebugMap = onExportDebugMap,
                onExportToEditor = onExportToEditor,
                onExportActions = onExportActions,
                onImportActions = onImportActions,
                onTabSelected = onTabSelected,
                isVignetteCenterMode = isVignetteCenterMode,
                onVignetteCenterModeChange = onVignetteCenterModeChange,
                isLensFlareMoveMode = isLensFlareMoveMode,
                onLensFlareMoveModeChange = onLensFlareMoveModeChange,
                healRadiusPx = healRadiusPx,
                onHealRadiusChange = { component.healing.setHealRadius(it) },
                healActive = healActive,
                onHealActiveChange = { component.healing.setHealActive(it) },
                isHealing = isHealing,
                healCount = healCount,
                onUndoLastHeal = onUndoLastHeal,
                onHealApply = onHealApply,
                onHealCancel = onHealCancel,
                isNonRawSource = component.isNonRawSource.value,
                isJpegSource = component.isJpegSource.value,
                onSaveEditAsLut = onSaveEditAsLut,
                segmentationMasks = segmentationMasks,
                onMaskModeActive = onMaskModeActive,
                showMaskOverlay = isMaskModeActive,
                onShowMaskOverlayChange = onShowMaskOverlayChange,
                brushMode = brushMode,
                onBrushModeChange = onBrushModeChange,
                sharpSpread = sharpSpread,
                onSharpSpreadChange = onSharpSpreadChange,
                onFillSharp = onFillSharp,
                onSegmentationNeeded = onSegmentationNeeded,
                subjectSegBusy = subjectSegBusy,
                subjectDetected = subjectDetected,
                imageLongSide = imageLongSide,
                onLightAuto = onLightAuto,
                onLightAutoOff = onLightAutoOff,
                onLightBasicAuto = onLightBasicAuto,
                lightAutoEnabled = lightAutoEnabled,
                lightAeActive = lightAeActive,
                onLightAeProtectionChange = onLightAeProtectionChange,
                brushSize = brushSize,
                onBrushSize = onBrushSize,
                brushIntensity = brushIntensity,
                onBrushIntensity = onBrushIntensity,
                brushFeather = brushFeather,
                onBrushFeather = onBrushFeather,
                colorTolerance = colorTolerance,
                onColorToleranceChange = onColorToleranceChange,
                colorRange = colorRange,
                onColorRangeChange = { component.masking.setMaskColorRange(it) },
                colorFeather = colorFeather,
                onColorFeatherChange = { component.masking.setMaskColorFeather(it) },
                colorSampleArgb = colorSamples.firstOrNull() ?: 0,
                onClearColorSamples = onClearColorSamples,
                chromaSubtractMode = chromaSubtractMode,
                hasMask = hasMask,
                onClearMask = onClearMask,
                onFillSubject = onFillSubject,
                onFillBackground = onFillBackground,
                onRemoveSubject = onRemoveSubject,
                onRemoveBackground = onRemoveBackground,
                onInvertMask = onInvertMask,
                hasMulticlass = multiclassMasks != null,
                onFillHair = { onFillDetectedClass(MaskClass.Hair) },
                onFillBodySkin = { onFillDetectedClass(MaskClass.BodySkin) },
                onFillFaceSkin = { onFillDetectedClass(MaskClass.FaceSkin) },
                onFillClothes = { onFillDetectedClass(MaskClass.Clothes) },
                onRemoveHair = { onRemoveDetectedClass(MaskClass.Hair) },
                onRemoveBodySkin = { onRemoveDetectedClass(MaskClass.BodySkin) },
                onRemoveFaceSkin = { onRemoveDetectedClass(MaskClass.FaceSkin) },
                onRemoveClothes = { onRemoveDetectedClass(MaskClass.Clothes) },
                hasCityscapes = cityscapesMasks != null,
                onFillBuildingWall = { onFillDetectedClass(MaskClass.Buildings) },
                onFillVegetation = { onFillDetectedClass(MaskClass.Vegetation) },
                onFillTerrain = { onFillDetectedClass(MaskClass.Terrain) },
                onFillSky = { onFillDetectedClass(MaskClass.Sky) },
                onRemoveBuildingWall = { onRemoveDetectedClass(MaskClass.Buildings) },
                onRemoveVegetation = { onRemoveDetectedClass(MaskClass.Vegetation) },
                onRemoveTerrain = { onRemoveDetectedClass(MaskClass.Terrain) },
                onRemoveSky = { onRemoveDetectedClass(MaskClass.Sky) },
                includedMaskClasses = includedMaskClasses,
                primaryMaskClass = primaryMaskClass,
                primaryIsLuma = primaryIsLuma,
                primaryIsChroma = primaryIsChroma,
                onAddLuma = onAddLuma,
                onRemoveLuma = onRemoveLuma,
                onAddChroma = onAddChroma,
                onRemoveChroma = onRemoveChroma,
                isMulticlassLoading = multiclassLoading,
                isCityscapesLoading = cityscapesLoading,
                isFullResReady = isFullResReady,
                isFullResProcessing = isFullResProcessing,
                sceneAutoEnhanceEnabled = sceneAutoEnhanceEnabled,
                onSceneAutoEnhanceChange = onSceneAutoEnhanceChange,
            )
        }
    }
}
