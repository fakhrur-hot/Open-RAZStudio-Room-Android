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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.ui.edition.EditionCapabilities
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskBrushMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks

internal const val TAB_TONE_COLOR    = 0  // Exposure, Contrast, H/S/Whites/Blacks, WB, Tint, Dehaze
internal const val TAB_COLOR_TOOLS   = 1  // Saturation, Vibrance, HSL, Color Grading Wheels, Skintone
internal const val TAB_CURVES_LUT    = 2  // Tone Curves (Master/R/G/B) + finishing trims (CLAHE/WB/skintone…)
internal const val TAB_MASKS_LOCAL   = 3  // "Gradient" tab — Gradients only (vignette → TAB_VIGNETTE_PANE, masks → TAB_MASK_LAYERS)
internal const val TAB_EFFECTS       = 4  // Ambiance, Bokeh, Bloom, Blur, Mist/Glow, Orton, Sharpen
internal const val TAB_TEXTURE_GRAIN = 5  // Film Grain, Detail Grain, Haxademic Grain, CA, Dither
internal const val TAB_ACTIONS       = 6  // 4 CPU action buttons + GPU/CPU card containers
internal const val TAB_LUT1          = 11 // 3D LUT slot 1 ("LUT 1 Corr", applied once)
internal const val TAB_LUT2          = 12 // 3D LUT slot 2 ("LUT 2 Color", applied once)
internal const val TAB_LUT_ADJ       = 13 // LUT Adjustments (CLAHE/WB/skintone/dehaze trims)
internal const val TAB_MASK_LAYERS   = 14 // Brush-mask painting + per-mask adjustments (split from Local)
internal const val TAB_VIGNETTE_PANE = 15 // Vignette (split from Local; the old Local tab is now Gradient-only)

// Legacy constants — kept so old RawAction.tabIndex values stored in actions stack
// continue to resolve; the tab strip no longer shows these as individual entries.
internal const val TAB_LIGHT       = 1   // was old slot 1 — now absorbed into TAB_TONE_COLOR
internal const val TAB_LUT         = 2   // was old slot 2 — now absorbed into TAB_CURVES_LUT
internal const val TAB_TONEMAP     = 3   // was old slot 3 — now absorbed into TAB_TONE_COLOR
internal const val TAB_TONE_CURVES = 4   // was old slot 4 — now absorbed into TAB_CURVES_LUT
internal const val TAB_COLOR       = 5   // was old slot 5 — now absorbed into TAB_COLOR_TOOLS
internal const val TAB_DETAILS     = 6   // was old slot 6 — now absorbed into TAB_TEXTURE_GRAIN
internal const val TAB_VIGNETTE    = 7   // was old slot 7 — now absorbed into TAB_MASKS_LOCAL
internal const val TAB_GRADIENT    = 8   // was old slot 8 — now absorbed into TAB_MASKS_LOCAL
internal const val TAB_MASK        = 9   // was old slot 9 — now absorbed into TAB_MASKS_LOCAL
internal const val TAB_HEAL        = 10  // was old slot 10 — Heal moved to export screen
// TAB_GLAMOUR_GLOW removed — Snapseed-port attempt didn't produce usable output.

/**
 * Full adjustment panel: 7 tabs with PhotoEditor-style pending-tab locking.
 *
 * When any slider changes within a tab, [pendingTab] is set to that tab, locking all
 * other tabs (except TAB_ACTIONS which is always accessible).
 * Apply commits the current [macro] delta as a [RawAction] card and navigates back to Actions.
 * Cancel discards the delta and navigates back to Actions.
 *
 * Tab layout (tasks 9.1–9.4):
 *   0 TAB_TONE_COLOR    — Light (RawLightTab) + Tonemap (RawTonemapTab)
 *   1 TAB_COLOR_TOOLS   — Color (RawColorTab)
 *   2 TAB_CURVES_LUT    — Tone Curves (RawToneCurvesTab) + LUT (RawLutTab)
 *   3 TAB_MASKS_LOCAL   — "Gradient" tab: Gradient (RawGradientTab) only
 *  15 TAB_VIGNETTE_PANE — "Vignette" tab: Vignette (RawVignetteTab)
 *   4 TAB_EFFECTS       — Effects (RawEffectsTab)
 *   5 TAB_TEXTURE_GRAIN — Details / grain (RawDetailTab)
 *   6 TAB_ACTIONS       — Action stack (RawActionsTab) + 4 CPU action buttons
 */
@Composable
internal fun RawAdjustmentPanel(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    previewBitmap: Bitmap?,
    /** 256-bucket graded-frame luma histogram for the Tone Curves backdrop. */
    gradedHistogram: IntArray? = null,
    actions: List<RawAction>,
    onApplyAction: (label: String, tabIndex: Int, macro: UserMacro) -> Unit,
    onCancelAction: () -> Unit,
    /**
     * Auto-apply (all non-mask tabs): fold of a tab's committed cards, so the
     * sliders read back the values the user left — no Apply button, no reset.
     */
    composeTabMacro: (Int) -> UserMacro = { UserMacro() },
    /**
     * Auto-apply: replace a non-mask tab's cards live on every slider move. The
     * Mask tab is exempt — it keeps the legacy paint→Apply commit below.
     */
    onReplaceTabCards: (Int, List<Pair<String, UserMacro>>) -> Unit = { _, _ -> },
    onLoadAction: (RawAction) -> Unit,
    /**
     * Re-add an action that was deleted by a previous [onLoadAction]
     * call. The [originalIndex] is the position the action occupied
     * before the delete so the restored card lands back in its
     * original layer order rather than at the bottom of the stack.
     * Used by the Actions-tap-to-edit flow: tapping a card deletes
     * the original (the in-flight delta will replace it on Apply) but
     * stores it as the "loaded original" so Cancel can restore it
     * without losing the user's prior edit.
     */
    onRestoreAction: (originalIndex: Int, action: RawAction) -> Unit,
    onDeleteAction: (id: String, keepStorage: Boolean) -> Unit,
    onEyeToggleAction: (String) -> Unit,
    onToggleLockAction: (String) -> Unit,
    presets: List<com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawPresetsStorage.Preset>,
    onSavePreset: (String) -> Boolean,
    onLoadPreset: (Int) -> Unit,
    onDeletePreset: (Int) -> Unit,
    onExportDebugMap: (Int) -> Unit = {},
    onExportToEditor: () -> Unit,
    onExportActions: () -> Unit,
    onImportActions: () -> Unit,
    /** Settings clipboard (Lightroom Copy/Paste Settings + Apply from previous). */
    onCopySettings: () -> Unit = {},
    onPasteSettings: () -> Unit = {},
    canPasteSettings: Boolean = false,
    onApplyPrevious: () -> Unit = {},
    canApplyPrevious: Boolean = false,
    /** Bake the current edit into a LUT (saved to User's Lut); returns the saved
     *  name or null. Surfaced as a "Save Edit as LUT" button in the LUT picker. */
    onSaveEditAsLut: (suspend (String) -> String?)? = null,
    segmentationMasks: RawSegmentationMasks?,
    onMaskModeActive: (Boolean) -> Unit,
    /**
     * Current state of the "Show" toggle in the Mask tab. When true,
     * the mask overlay is rendered on the canvas and Draw / Erase /
     * Tap-Select are interactable. When false, the canvas behaves as
     * a normal pan/pinch surface and the brush modes are disabled.
     * Independent from the Mask tab being selected.
     */
    showMaskOverlay: Boolean,
    onShowMaskOverlayChange: (Boolean) -> Unit,
    brushMode: MaskBrushMode,
    onBrushModeChange: (MaskBrushMode) -> Unit,
    sharpSpread: Float = 0f,
    onSharpSpreadChange: (Float) -> Unit = {},
    onFillSharp: () -> Unit = {},
    /** As-shot Kelvin from source EXIF; 0 = unknown, Color tab uses 5500. */
    asShotKelvin: Int = 0,
    /** Scene-adaptive auto-enhance toggle state + handler (Color tab). */
    sceneAutoEnhanceEnabled: Boolean = false,
    onSceneAutoEnhanceChange: (Boolean) -> Unit = {},
    /**
     * Invoked when the user enters a tab whose features need subject/segmentation
     * masks (Mask Layers, local Gradient). Segmentation now runs on-demand (it was
     * the OOM driver when eager), so this kicks off the ONNX chain as the user
     * navigates in — masks then arrive while they work. Idempotent downstream.
     */
    onSegmentationNeeded: () -> Unit = {},
    /** True while segmentation is running and no subject mask is ready yet —
     *  greys out subject-mask-dependent controls (Bokeh, subject/background
     *  Vignette & Gradient) until detection finishes. */
    subjectSegBusy: Boolean = false,
    /** False once detection finished with an empty subject matte (see RawEffectsTab). */
    subjectDetected: Boolean = true,
    /**
     * Stage A long side (max of previewWidth × previewHeight). Used by Bloom
     * density chips so Pro-Mist strength tracks the loaded photo size.
     */
    imageLongSide: Int = CinematicBloomProcessor.REF_LONG_SIDE,
    onLightAuto: () -> Unit = {},
    onLightAutoOff: () -> Unit = {},
    onLightBasicAuto: () -> Unit = {},
    lightAutoEnabled: Boolean = true,
    lightAeActive: Boolean = false,
    onLightAeProtectionChange: (Float) -> Unit = {},
    lightAeLocked: Boolean = false,
    smartBright: Float = 0f,
    smartBrightLocked: Boolean = false,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    brushIntensity: Float,
    onBrushIntensity: (Float) -> Unit,
    brushFeather: Float,
    onBrushFeather: (Float) -> Unit,
    // Color-range ("Select Color") mask tool — forwarded to RawMaskTab.
    colorTolerance: Float = 50f,
    onColorToleranceChange: (Float) -> Unit = {},
    colorRange: Float = 60f,
    onColorRangeChange: (Float) -> Unit = {},
    colorFeather: Float = 30f,
    onColorFeatherChange: (Float) -> Unit = {},
    colorSampleArgb: Int = 0,
    onClearColorSamples: () -> Unit = {},
    chromaSubtractMode: Boolean = false,
    hasMask: Boolean,
    onClearMask: () -> Unit,
    onFillSubject: () -> Unit,
    onFillBackground: () -> Unit,
    onRemoveSubject: () -> Unit,
    onRemoveBackground: () -> Unit,
    onInvertMask: () -> Unit,
    /** MediaPipe multiclass class-fill callbacks (Mask tab). */
    hasMulticlass: Boolean = false,
    onFillHair: () -> Unit = {},
    onFillBodySkin: () -> Unit = {},
    onFillFaceSkin: () -> Unit = {},
    onFillClothes: () -> Unit = {},
    onRemoveHair: () -> Unit = {},
    onRemoveBodySkin: () -> Unit = {},
    onRemoveFaceSkin: () -> Unit = {},
    onRemoveClothes: () -> Unit = {},
    /** Cityscapes 4-class fill callbacks (Mask tab). */
    hasCityscapes: Boolean = false,
    onFillBuildingWall: () -> Unit = {},
    onFillVegetation:   () -> Unit = {},
    onFillTerrain:      () -> Unit = {},
    onFillSky:          () -> Unit = {},
    onRemoveBuildingWall: () -> Unit = {},
    onRemoveVegetation:   () -> Unit = {},
    onRemoveTerrain:      () -> Unit = {},
    onRemoveSky:          () -> Unit = {},
    /** Per-class inclusion set for split-button blue-state tracking. */
    includedMaskClasses: Set<com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass> = emptySet(),
    /** The "prime" mask class — the first one selected. Its button shows a filled star indicator. */
    primaryMaskClass: com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass? = null,
    primaryIsLuma: Boolean = false,
    primaryIsChroma: Boolean = false,
    /** Luma/Chroma Add+Remove — host implements clear-vs-carve (maskLumCombine). */
    onAddLuma: () -> Unit = {},
    onRemoveLuma: () -> Unit = {},
    onAddChroma: () -> Unit = {},
    onRemoveChroma: () -> Unit = {},
    isMulticlassLoading: Boolean = false,
    isCityscapesLoading: Boolean = false,
    isFullResReady: Boolean,
    isFullResProcessing: Boolean,
    onTabSelected: (Int) -> Unit = {},
    isVignetteCenterMode: Boolean = false,
    onVignetteCenterModeChange: (Boolean) -> Unit = {},
    isLensFlareMoveMode: Boolean = false,
    onLensFlareMoveModeChange: (Boolean) -> Unit = {},
    /** When true, hide RAW-only tabs (currently just Light — depends on sensor
     *  data) AND swap the Colour tab's Kelvin readout for a relative
     *  Temperature slider (a developed file has no as-shot white balance). */
    isNonRawSource: Boolean = false,
    /**
     * Heal-tab state. The tab itself only owns the radius slider; the
     * actual tap-to-heal interaction lives on the main preview canvas
     * which the host wires up when the Heal screen is active (RawExportScreen).
     */
    healRadiusPx: Float = 60f,
    onHealRadiusChange: (Float) -> Unit = {},
    /**
     * Explicit "Enable touch to heal" toggle, mirrors the Vignette tab's
     * "Move center" button. When on, the host suspends pinch/pan/zoom
     * on the canvas so a tap is routed to the heal pipeline instead of
     * racing with the gesture detectors.
     */
    healActive: Boolean = false,
    onHealActiveChange: (Boolean) -> Unit = {},
    isHealing: Boolean = false,
    healCount: Int = 0,
    onUndoLastHeal: () -> Unit = {},
    /** Surfaced so the host (RawEditorContent) knows when Heal is active. */
    onHealModeActive: (Boolean) -> Unit = {},
    /**
     * True when at least one heal tap has been made since the Heal tab
     * was entered but not yet Applied or Cancelled. Drives the Apply /
     * Cancel bar (same shape as the slider-tab pending bar) so the user
     * can commit a Heal action card or revert to the unhealed state.
     */
    healDirty: Boolean = false,
    /** Commit the in-progress heals as a Heal action card. */
    onHealApply: () -> Unit = {},
    /** Discard the in-progress heals; restore the pre-edit overlay. */
    onHealCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(TAB_ACTIONS) }
    var pendingTab  by remember { mutableStateOf<Int?>(null) }

    // Tab scroll memory: each tab keeps its own scroll position so switching
    // back doesn't reset to the top. Identity keys (Int) match the TAB_* constants.
    val tabScrollStates = remember { mutableMapOf<Int, androidx.compose.foundation.ScrollState>() }
    val currentScrollState = tabScrollStates.getOrPut(selectedTab) {
        androidx.compose.foundation.ScrollState(initial = 0)
    }

    // When the user taps an action card to re-edit it, that card is
    // deleted from the stack so the new Apply can replace it cleanly.
    // We hold onto the deleted RawAction here (with its original index)
    // so Cancel can re-insert it at the right layer position. Null in
    // the manual-Apply flow (slider drag → Apply on the same tab)
    // where there's no original to restore.
    data class LoadedOriginal(val index: Int, val action: RawAction)
    var loadedOriginal by remember { mutableStateOf<LoadedOriginal?>(null) }

    // 7-tab strip. Actions is first (leftmost); locking check still uses the constant value
    // not the row position, so TAB_ACTIONS always stays accessible regardless of order.
    val tabs = listOf(
        TAB_ACTIONS       to "Actions",
        TAB_TONE_COLOR    to "Tone",
        TAB_COLOR_TOOLS   to "Color",
        TAB_CURVES_LUT    to "Curves",
        TAB_LUT1          to "LUT",
        // LUT 2 (TAB_LUT2) hidden by request — single LUT slot. Its content
        // case + the chain-bake path are kept so presets carrying a 2nd LUT
        // still render, but it's no longer selectable from the strip.
        TAB_LUT_ADJ       to "LUT Adj",
        TAB_VIGNETTE_PANE to "Vignette",
        TAB_MASKS_LOCAL   to "Gradient",
        TAB_MASK_LAYERS   to "Mask",
        TAB_EFFECTS       to "FX",
        TAB_TEXTURE_GRAIN to "Details",
    )

    LaunchedEffect(Unit) {
        if (!EditionCapabilities.isRawTabWired(selectedTab)) selectedTab = TAB_ACTIONS
    }

    // TAB_LIGHT no longer has its own slot in the strip (it is merged into TAB_TONE_COLOR).
    // If an old stored tabIndex somehow lands us on TAB_LIGHT (legacy value = 1 = TAB_COLOR_TOOLS)
    // we don't need a guard here; the when() below maps both new constants correctly.

    LaunchedEffect(selectedTab) {
        // Show/Draw/Erase state is owned by user-tapped buttons in RawMaskTab.
        // Do NOT auto-enable Show on Mask tab entry — overlay starts hidden.
        // Leaving the Mask tab forces overlay off so canvas reverts to clean pan/pinch.
        if (selectedTab != TAB_MASK_LAYERS) {
            onShowMaskOverlayChange(false)
            onMaskModeActive(false)
        } else {
            // Fresh mask tab entry (not re-editing an existing card) —
            // clear any stale in-flight mask from a prior session so the
            // user starts with a blank canvas.
            if (pendingTab == null) {
                onCancelAction()
            }
        }
        // Heal is no longer on this panel (moved to export screen); always report inactive.
        onHealModeActive(false)
        onTabSelected(selectedTab)
        // Vignette center mode only applies while the Vignette tab is open.
        if (selectedTab != TAB_VIGNETTE_PANE) onVignetteCenterModeChange(false)
        if (selectedTab != TAB_EFFECTS) onLensFlareMoveModeChange(false)
    }

    // Legacy pending-tab tracking now applies ONLY to the Mask tab — it alone
    // keeps the paint→Apply/Cancel commit (and the lock that prevents leaving a
    // half-painted mask). Every other tab auto-applies via [onLiveChange] and
    // never sets pendingTab, so the strip never locks while sliding them.
    fun onMacroChangeTracked(newMacro: UserMacro) {
        if (pendingTab == null && selectedTab == TAB_MASK_LAYERS) {
            pendingTab = selectedTab
        }
        onMacroChange(newMacro)
    }

    fun isLiveTab(tab: Int) = tab != TAB_ACTIONS && tab != TAB_MASK_LAYERS

    /**
     * Splits a delta [UserMacro] into individual (label, single-param macro) pairs — one per
     * changed parameter. Each pair becomes its own action card so the Actions tab shows granular
     * history like "Tone · Exposure +35" and "Tone · Shadows -20" as separate cards rather than
     * one combined "Tone" card.
     */
    fun buildIndividualCards(tabIndex: Int, m: UserMacro): List<Pair<String, UserMacro>> {
        val tab = when (tabIndex) {
            TAB_TONE_COLOR    -> "Tone"
            TAB_COLOR_TOOLS   -> "Color"
            TAB_CURVES_LUT    -> "LUT"
            TAB_VIGNETTE_PANE -> "Vignette"
            TAB_MASKS_LOCAL   -> "Gradient"
            TAB_MASK_LAYERS   -> "Mask"
            TAB_EFFECTS       -> "FX"
            TAB_TEXTURE_GRAIN -> "Details"
            else              -> "Action"
        }
        val d = UserMacro()
        val result = mutableListOf<Pair<String, UserMacro>>()

        fun fv(v: Float, scale: Float = 1f, decimals: Int = 0): String =
            if (decimals > 0) "%.${decimals}f".format(v * scale)
            else "${(v * scale).toInt()}"

        fun add(label: String, single: UserMacro) {
            result += "$tab · $label" to single
        }
        fun addF(v: Float, uiName: String, default: Float = 0f, scale: Float = 1f,
                 decimals: Int = 0, single: UserMacro) {
            if (v != default) add("$uiName ${fv(v, scale, decimals)}", single)
        }
        fun addB(v: Boolean, uiName: String, default: Boolean = false, single: UserMacro) {
            if (v != default) add("$uiName ${if (v) "On" else "Off"}", single)
        }

        when (tabIndex) {
            TAB_TONE_COLOR -> {
                addF(m.exposure,    "Exposure",    scale=100f, single=d.copy(exposure=m.exposure))
                addF(m.contrast,    "Contrast",    scale=100f, single=d.copy(contrast=m.contrast))
                addF(m.highlights,  "Highlights",  scale=100f, single=d.copy(highlights=m.highlights))
                addF(m.shadows,     "Shadows",     scale=100f, single=d.copy(shadows=m.shadows))
                addF(m.whites,      "Whites",      scale=100f, single=d.copy(whites=m.whites))
                addF(m.blacks,      "Blacks",      scale=100f, single=d.copy(blacks=m.blacks))
                addF(m.contrastBoost, "Contrast+", scale=100f, single=d.copy(contrastBoost=m.contrastBoost))
                addF(m.dehaze,      "Dehaze",      single=d.copy(dehaze=m.dehaze))
                if (m.whiteBalance != d.whiteBalance)
                    add("WB ${m.whiteBalance}K", d.copy(whiteBalance=m.whiteBalance))
                addF(m.tint,        "Tint",        scale=100f, single=d.copy(tint=m.tint))
                addF(m.tonemapExposure,   "TM Exp",   scale=100f, single=d.copy(tonemapExposure=m.tonemapExposure))
                addF(m.tonemapHighlights, "TM Hi",    scale=100f, single=d.copy(tonemapHighlights=m.tonemapHighlights))
                addF(m.tonemapShadows,    "TM Sh",    scale=100f, single=d.copy(tonemapShadows=m.tonemapShadows))
                addF(m.highlightTemperature, "Hi Temp", scale=100f, single=d.copy(highlightTemperature=m.highlightTemperature))
                addF(m.shadowTemperature,    "Sh Temp", scale=100f, single=d.copy(shadowTemperature=m.shadowTemperature))
                addF(m.highlightsSubject,    "Hi Subj", scale=100f, single=d.copy(highlightsSubject=m.highlightsSubject))
                addF(m.shadowsSubject,       "Sh Subj", scale=100f, single=d.copy(shadowsSubject=m.shadowsSubject))
                // Smart Bright [0..4] → its own card "Tone · Smart Bright <pct>".
                // scale=25 maps 0..4 to 0..100 for the label. Its presence here
                // is what makes the Apply bar appear when only Smart Bright moves.
                addF(m.smartBright,          "Smart Bright", scale=25f, single=d.copy(smartBright=m.smartBright))
                // Film Response Recovery / Fill Light — Tone tab home (was LUT ADV).
                addF(m.filmResponse.recovery,  "HL Recovery", single=d.copy(
                    filmResponse = d.filmResponse.copy(recovery = m.filmResponse.recovery)))
                addF(m.filmResponse.fillLight, "Fill Light",  single=d.copy(
                    filmResponse = d.filmResponse.copy(fillLight = m.filmResponse.fillLight)))
            }
            TAB_COLOR_TOOLS -> {
                // Color Pop strength (Off/Low/Med/High). Only registers a card when
                // not Off (0); the label carries the level so the Actions tab is clear.
                if (m.smartColorEnhance > 0f) {
                    val lvl = when {
                        m.smartColorEnhance <= UserMacro.COLOR_POP_LOW -> "Low"
                        m.smartColorEnhance <= UserMacro.COLOR_POP_MED -> "Med"
                        else                                           -> "High"
                    }
                    add("Color Pop $lvl", d.copy(smartColorEnhance = m.smartColorEnhance))
                }
                // White balance + tint live in the Color tab UI (RawColorTab),
                // so they must register here — not only under TAB_TONE_COLOR.
                if (m.whiteBalance != d.whiteBalance)
                    add("WB ${m.whiteBalance}K", d.copy(whiteBalance=m.whiteBalance))
                addF(m.tint,          "Tint",       scale=100f, single=d.copy(tint=m.tint))
                addF(m.saturation,    "Saturation", scale=100f, single=d.copy(saturation=m.saturation))
                addF(m.vibrance,      "Vibrance",   scale=100f, single=d.copy(vibrance=m.vibrance))
                addF(m.colorDensity,  "Density",    single=d.copy(colorDensity=m.colorDensity))
                addF(m.skintoneWarm,  "Sk Warm",    single=d.copy(skintoneWarm=m.skintoneWarm))
                addF(m.skintoneSmooth,"Sk Smooth",  single=d.copy(skintoneSmooth=m.skintoneSmooth))
                addF(m.skintoneLuma,  "Sk Luma",    single=d.copy(skintoneLuma=m.skintoneLuma))
                data class HslEntry(val name: String, val h: Float, val s: Float, val l: Float,
                                    val macro: UserMacro)
                listOf(
                    HslEntry("Red",   m.hslRedHue,     m.hslRedSat,     m.hslRedLum,     d.copy(hslRedHue=m.hslRedHue,hslRedSat=m.hslRedSat,hslRedLum=m.hslRedLum)),
                    HslEntry("Orange",m.hslOrangeHue,  m.hslOrangeSat,  m.hslOrangeLum,  d.copy(hslOrangeHue=m.hslOrangeHue,hslOrangeSat=m.hslOrangeSat,hslOrangeLum=m.hslOrangeLum)),
                    HslEntry("Yellow",m.hslYellowHue,  m.hslYellowSat,  m.hslYellowLum,  d.copy(hslYellowHue=m.hslYellowHue,hslYellowSat=m.hslYellowSat,hslYellowLum=m.hslYellowLum)),
                    HslEntry("Yel-Grn",m.hslExt.yellowGreenHue,m.hslExt.yellowGreenSat,m.hslExt.yellowGreenLum,d.copy(hslExt = d.hslExt.copy(yellowGreenHue=m.hslExt.yellowGreenHue,yellowGreenSat=m.hslExt.yellowGreenSat,yellowGreenLum=m.hslExt.yellowGreenLum))),
                    HslEntry("Green", m.hslGreenHue,   m.hslGreenSat,   m.hslGreenLum,   d.copy(hslGreenHue=m.hslGreenHue,hslGreenSat=m.hslGreenSat,hslGreenLum=m.hslGreenLum)),
                    HslEntry("Aqua",  m.hslAquaHue,    m.hslAquaSat,    m.hslAquaLum,    d.copy(hslAquaHue=m.hslAquaHue,hslAquaSat=m.hslAquaSat,hslAquaLum=m.hslAquaLum)),
                    HslEntry("Blue",  m.hslBlueHue,    m.hslBlueSat,    m.hslBlueLum,    d.copy(hslBlueHue=m.hslBlueHue,hslBlueSat=m.hslBlueSat,hslBlueLum=m.hslBlueLum)),
                    HslEntry("Purple",m.hslExt.purpleHue,  m.hslExt.purpleSat,  m.hslExt.purpleLum,  d.copy(hslExt = d.hslExt.copy(purpleHue=m.hslExt.purpleHue,purpleSat=m.hslExt.purpleSat,purpleLum=m.hslExt.purpleLum))),
                    HslEntry("Magenta",m.hslExt.magentaHue,m.hslExt.magentaSat, m.hslExt.magentaLum, d.copy(hslExt = d.hslExt.copy(magentaHue=m.hslExt.magentaHue,magentaSat=m.hslExt.magentaSat,magentaLum=m.hslExt.magentaLum))),
                ).forEach { e ->
                    if (e.h != 0f || e.s != 0f || e.l != 0f) add("HSL ${e.name}", e.macro)
                }
                // Color Grading wheels — one card per non-neutral wheel. Without
                // these, wheel/slider edits never become cards, so the auto-apply
                // round-trip has nothing to fold back and the controlled UI freezes.
                if (m.cgGlobal.r != 0f || m.cgGlobal.g != 0f || m.cgGlobal.b != 0f || m.cgGlobal.sat != 0f)
                    add("Wheel Global", d.copy(cgGlobal = m.cgGlobal))
                if (m.cgShadows.r != 0f || m.cgShadows.g != 0f || m.cgShadows.b != 0f || m.cgShadows.sat != 0f)
                    add("Wheel Shadows", d.copy(cgShadows = m.cgShadows))
                if (m.cgMidtones.r != 0f || m.cgMidtones.g != 0f || m.cgMidtones.b != 0f || m.cgMidtones.sat != 0f)
                    add("Wheel Midtones", d.copy(cgMidtones = m.cgMidtones))
                if (m.cgHighlights.r != 0f || m.cgHighlights.g != 0f || m.cgHighlights.b != 0f || m.cgHighlights.sat != 0f)
                    add("Wheel Highlights", d.copy(cgHighlights = m.cgHighlights))
            }
            TAB_CURVES_LUT -> {
                // Curves tab = Tone Curves graph only. LUT picking → LUT 1/2 tabs;
                // finishing trims → LUT ADJ tab; ambiance → Details tab.
                val defaultCurve = d.toneCurvePoints
                if (m.toneCurvePoints != defaultCurve || m.filmCurve != d.filmCurve)
                    add("Curves", d.copy(
                        toneCurvePoints = m.toneCurvePoints,
                        toneCurveLumaMode = m.toneCurveLumaMode,
                        filmCurve = m.filmCurve,
                    ))
            }
            TAB_LUT_ADJ -> {
                // CLAHE UI hidden — still register cards if sidecar/preset sets them.
                addF(m.claheShadowsBoost,    "CLAHE Shadows",    scale=100f, single=d.copy(claheShadowsBoost=m.claheShadowsBoost))
                addF(m.claheHighlightsBoost, "CLAHE Highlights", scale=100f, single=d.copy(claheHighlightsBoost=m.claheHighlightsBoost))
                addF(m.highlightTemperature, "Hi Temp",          scale=100f, single=d.copy(highlightTemperature=m.highlightTemperature))
                addF(m.highlightTint,        "Hi Tint",          scale=100f, single=d.copy(highlightTint=m.highlightTint))
                addF(m.shadowTemperature,    "Sh Temp",          scale=100f, single=d.copy(shadowTemperature=m.shadowTemperature))
                addF(m.shadowTint,           "Sh Tint",          scale=100f, single=d.copy(shadowTint=m.shadowTint))
                addF(m.colorDensity,  "Color Density", single=d.copy(colorDensity=m.colorDensity))
                addF(m.skintoneWarm,  "Sk Warm",       single=d.copy(skintoneWarm=m.skintoneWarm))
                addF(m.skintoneSmooth,"Sk Smooth",     single=d.copy(skintoneSmooth=m.skintoneSmooth))
                addF(m.skintoneLuma,  "Sk Luma",       single=d.copy(skintoneLuma=m.skintoneLuma))
                addF(m.dehaze,       "Dehaze",   single=d.copy(dehaze=m.dehaze))
            }
            TAB_LUT1, TAB_LUT2 -> {
                // One card per LUT slot. The pending macro carries the in-flight
                // slot edit (lutEdited + lutSlot); replay folds it into slot 1/2.
                if (m.lutEdited) {
                    val slot = m.lutSlot.coerceIn(1, 2)
                    val nm = if (m.lutCubeUri.isEmpty()) "Off"
                             else m.lutCubeUri.substringAfterLast('/').substringBeforeLast('.').take(16)
                    add("LUT $slot · $nm ${(m.lutIntensity * 100).toInt()}%",
                        d.copy(lutSlot = slot, lutCubeUri = m.lutCubeUri,
                               lutIntensity = m.lutIntensity, lutEdited = true))
                }
            }
            TAB_VIGNETTE_PANE -> {
                // Vignette: register whenever amount, segmentation (Smart Vignette),
                // or the effect mode is non-default — and carry every vignette field
                // (feather/intensity/center/segmentation) so nothing is dropped.
                val hasVig = m.vignetteAmount != d.vignetteAmount ||
                    m.vignetteSegmentation != d.vignetteSegmentation ||
                    m.vignetteEffect != d.vignetteEffect
                if (hasVig) add("Vignette ${(m.vignetteAmount).toInt()}", d.copy(
                    vignetteAmount=m.vignetteAmount, vignetteFeather=m.vignetteFeather,
                    vignetteIntensity=m.vignetteIntensity, vignetteEffect=m.vignetteEffect,
                    vignetteCenterX=m.vignetteCenterX, vignetteCenterY=m.vignetteCenterY,
                    vignetteCenterAutoSnapped=m.vignetteCenterAutoSnapped,
                    vignetteSegmentation=m.vignetteSegmentation,
                ))
            }
            TAB_MASKS_LOCAL -> {
                // Gradient: detect ANY non-default gradient field (primary + 2nd
                // gradient + angle + blend/applyTo) and carry the COMPLETE gradient
                // state in one card so edits like the 2nd colour stop, rotation,
                // blend mode and subject-target aren't lost on Apply.
                val hasGrad =
                    m.gradientAngle != d.gradientAngle ||
                    m.gradientTopIntensity != 0f    || m.gradientTopIntensity2 != 0f    ||
                    m.gradientBottomIntensity != 0f || m.gradientBottomIntensity2 != 0f ||
                    m.gradientLeftIntensity != 0f   || m.gradientLeftIntensity2 != 0f   ||
                    m.gradientRightIntensity != 0f  || m.gradientRightIntensity2 != 0f  ||
                    m.gradientTopApplyTo != d.gradientTopApplyTo ||
                    m.gradientBottomApplyTo != d.gradientBottomApplyTo ||
                    m.gradientLeftApplyTo != d.gradientLeftApplyTo ||
                    m.gradientRightApplyTo != d.gradientRightApplyTo ||
                    m.gradientTopBlendMode != d.gradientTopBlendMode ||
                    m.gradientBottomBlendMode != d.gradientBottomBlendMode ||
                    m.gradientLeftBlendMode != d.gradientLeftBlendMode ||
                    m.gradientRightBlendMode != d.gradientRightBlendMode
                if (hasGrad) add("Gradient", d.copy(
                    gradientAngle=m.gradientAngle,
                    gradientTopIntensity=m.gradientTopIntensity,gradientTopLength=m.gradientTopLength,
                    gradientTopFeather=m.gradientTopFeather,gradientTopTintColor=m.gradientTopTintColor,
                    gradientTopTintLuminosity=m.gradientTopTintLuminosity,gradientTopBlendMode=m.gradientTopBlendMode,
                    gradientTopApplyTo=m.gradientTopApplyTo,gradientTopEnable2=m.gradientTopEnable2,
                    gradientTopIntensity2=m.gradientTopIntensity2,gradientTopLength2=m.gradientTopLength2,
                    gradientTopFeather2=m.gradientTopFeather2,gradientTopTintColor2=m.gradientTopTintColor2,
                    gradientTopTintLuminosity2=m.gradientTopTintLuminosity2,
                    gradientBottomIntensity=m.gradientBottomIntensity,gradientBottomLength=m.gradientBottomLength,
                    gradientBottomFeather=m.gradientBottomFeather,gradientBottomTintColor=m.gradientBottomTintColor,
                    gradientBottomTintLuminosity=m.gradientBottomTintLuminosity,gradientBottomBlendMode=m.gradientBottomBlendMode,
                    gradientBottomApplyTo=m.gradientBottomApplyTo,gradientBottomEnable2=m.gradientBottomEnable2,
                    gradientBottomIntensity2=m.gradientBottomIntensity2,gradientBottomLength2=m.gradientBottomLength2,
                    gradientBottomFeather2=m.gradientBottomFeather2,gradientBottomTintColor2=m.gradientBottomTintColor2,
                    gradientBottomTintLuminosity2=m.gradientBottomTintLuminosity2,
                    gradientLeftIntensity=m.gradientLeftIntensity,gradientLeftLength=m.gradientLeftLength,
                    gradientLeftFeather=m.gradientLeftFeather,gradientLeftTintColor=m.gradientLeftTintColor,
                    gradientLeftTintLuminosity=m.gradientLeftTintLuminosity,gradientLeftBlendMode=m.gradientLeftBlendMode,
                    gradientLeftApplyTo=m.gradientLeftApplyTo,gradientLeftEnable2=m.gradientLeftEnable2,
                    gradientLeftIntensity2=m.gradientLeftIntensity2,gradientLeftLength2=m.gradientLeftLength2,
                    gradientLeftFeather2=m.gradientLeftFeather2,gradientLeftTintColor2=m.gradientLeftTintColor2,
                    gradientLeftTintLuminosity2=m.gradientLeftTintLuminosity2,
                    gradientRightIntensity=m.gradientRightIntensity,gradientRightLength=m.gradientRightLength,
                    gradientRightFeather=m.gradientRightFeather,gradientRightTintColor=m.gradientRightTintColor,
                    gradientRightTintLuminosity=m.gradientRightTintLuminosity,gradientRightBlendMode=m.gradientRightBlendMode,
                    gradientRightApplyTo=m.gradientRightApplyTo,gradientRightEnable2=m.gradientRightEnable2,
                    gradientRightIntensity2=m.gradientRightIntensity2,gradientRightLength2=m.gradientRightLength2,
                    gradientRightFeather2=m.gradientRightFeather2,gradientRightTintColor2=m.gradientRightTintColor2,
                    gradientRightTintLuminosity2=m.gradientRightTintLuminosity2,
                ))
            }
            TAB_MASK_LAYERS -> {
                // A painted mask is ONE layer carrying ALL its adjustments — it
                // must NOT be split per field. Each onApplyAction saves the brush
                // PNG under a new id, so splitting turned a single mask into
                // several PNG layers; worse, onApplyAction nulls maskBitmap after
                // the first card, so the remaining fields became orphaned cards
                // with NO mask (applied globally / lost). Two masks then blew past
                // the 4-layer budget and rendering broke. One mask = one card =
                // one PNG = one layer.
                val parts = buildList {
                    if (m.maskBrightness != 0f) add("B ${(m.maskBrightness * 100).toInt()}")
                    if (m.maskContrast != 0f)   add("C ${(m.maskContrast * 100).toInt()}")
                    if (m.maskTemperature != d.maskTemperature) add("T ${m.maskTemperature}")
                    if (m.maskTint != 0f)       add("Ti ${(m.maskTint * 100).toInt()}")
                    if (m.maskSaturation != 0f) add("S ${(m.maskSaturation * 100).toInt()}")
                    if (m.maskClarity != 0f)    add("Cl ${(m.maskClarity * 100).toInt()}")
                    if (m.maskSharpness != 0f)  add("Sh ${(m.maskSharpness * 100).toInt()}")
                    if (m.maskTone.highlights != 0f) add("HL ${m.maskTone.highlights.toInt()}")
                    if (m.maskTone.shadows != 0f)    add("Sd ${m.maskTone.shadows.toInt()}")
                    if (m.maskTone.whites != 0f)     add("Wh ${m.maskTone.whites.toInt()}")
                    if (m.maskTone.blacks != 0f)     add("Bk ${m.maskTone.blacks.toInt()}")
                    if (m.maskLumSpread != 0f)  add("Lum")
                }
                // The lum-range fields MUST commit with the card: omitting them
                // destroyed every Select-Luminance mask on Apply (card kept the
                // tone values but lumSpread=0 → the band definition vanished),
                // and a luma-base carve (lumCombine=1) degraded to bitmap-only —
                // applying the edit to exactly the region the user had removed.
                if (parts.isNotEmpty()) add(parts.joinToString(" "), d.copy(
                    maskBrightness  = m.maskBrightness,
                    maskContrast    = m.maskContrast,
                    maskTemperature = m.maskTemperature,
                    maskTint        = m.maskTint,
                    maskSaturation  = m.maskSaturation,
                    maskClarity     = m.maskClarity,
                    maskSharpness   = m.maskSharpness,
                    maskTone        = m.maskTone,
                    maskLumTarget   = m.maskLumTarget,
                    maskLumSpread   = m.maskLumSpread,
                    maskLumFeather  = m.maskLumFeather,
                    maskLumCombine  = m.maskLumCombine,
                ))
            }
            TAB_EFFECTS -> {
                // Bloom card carries density-tier params + fused glow + protect.
                if (m.ortonStrength != 0f || m.bloomExcludeSubject || m.cinematicMistTier != 0 ||
                    m.mistHalation != 0f || m.fxGlowStrength != 0f || m.fxGlowWarmth != 0f
                ) {
                    add(
                        "Bloom ${fv(m.ortonStrength, 100f, 0)}",
                        d.copy(
                            ortonStrength = m.ortonStrength,
                            bloomRadius = m.bloomRadius,
                            bloomShape = m.bloomShape,
                            mistTightness = m.mistTightness,
                            mistHalation = m.mistHalation,
                            cinematicMistTier = m.cinematicMistTier,
                            bloomExcludeSubject = m.bloomExcludeSubject,
                            subjectBloom = m.subjectBloom,
                            fxGlowStrength = m.fxGlowStrength,
                            fxGlowSpread = m.fxGlowSpread,
                            fxGlowWarmth = m.fxGlowWarmth,
                        ),
                    )
                }
                addF(m.filmRolloff,     "Film Rolloff", scale=100f, single=d.copy(filmRolloff=m.filmRolloff))
                addF(m.pushPull,        "Push/Pull", decimals=1, single=d.copy(pushPull=m.pushPull))
                if (m.bokehBlur != d.bokehBlur) add("Bokeh ${m.bokehBlur}", d.copy(bokehBlur=m.bokehBlur))
                if (m.lensFlare.brightness > 0f) add("Lens Flare", d.copy(lensFlare=m.lensFlare))
                if (m.colorShift.redX != 0f || m.colorShift.greenX != 0f || m.colorShift.blueX != 0f)
                    add("Color Shift", d.copy(colorShift=m.colorShift))
                                // Vintage Amount/Vig were missing from cards → live/export stayed 0 when
                // mixed with other FX cards (safety net only fires on mismatch; empty
                // Amount-only used full delta, but Amount+Bloom dropped Amount).
                addF(m.fxVintageStrength, "Vintage Amount", single=d.copy(fxVintageStrength=m.fxVintageStrength))
                addF(m.fxVintageFade,   "Tape Fade",  single=d.copy(fxVintageFade=m.fxVintageFade))
                addF(m.fxVintageMistIntensity, "Mist Intensity", single=d.copy(fxVintageMistIntensity=m.fxVintageMistIntensity))
                addF(m.fxVintageMistScale, "Mist Scale", default=1f, single=d.copy(fxVintageMistScale=m.fxVintageMistScale))
                addF(m.fxVintageTextureIntensity, "Tex Intensity", single=d.copy(fxVintageTextureIntensity=m.fxVintageTextureIntensity))
                addF(m.fxVintageTextureScale, "Tex Scale", default=1f, single=d.copy(fxVintageTextureScale=m.fxVintageTextureScale))
                // Mist UI (wash) hidden — still card legacy non-zero sidecars.
                addF(m.fxMist,          "Mist",    single=d.copy(fxMist=m.fxMist,fxMistWarmth=m.fxMistWarmth))
            }
            TAB_TEXTURE_GRAIN -> {
                addF(m.ambiance,        "Ambiance",scale=100f, single=d.copy(ambiance=m.ambiance))
                addF(m.sharpness,       "Sharpness",  single=d.copy(sharpness=m.sharpness))
                addF(m.clarity,         "Clarity",scale=100f, single=d.copy(clarity=m.clarity))
                addF(m.texture,         "Texture", scale=100f, single=d.copy(texture=m.texture))
                addF(m.filmGrain,       "Grain",   scale=100f, single=d.copy(filmGrain=m.filmGrain,filmGrainSize=m.filmGrainSize,filmGrainWashOut=m.filmGrainWashOut))
                addF(m.luminanceNR,     "Luma NR", scale=100f, single=d.copy(luminanceNR=m.luminanceNR))
                addF(m.colorNR,         "Color NR",scale=100f, single=d.copy(colorNR=m.colorNR))
                addF(m.blueNR,          "Blue NR", scale=100f, single=d.copy(blueNR=m.blueNR))
                addF(m.redNR,           "Red NR",  scale=100f, single=d.copy(redNR=m.redNR))
                addF(m.smartSharpness,  "Sharpness",scale=100f,single=d.copy(smartSharpness=m.smartSharpness))
                addF(m.smoothBackground,"Smooth BG",  scale=100f,single=d.copy(smoothBackground=m.smoothBackground))
            }
        }
        // Safety net: every edited field must be captured by a card above, or it
        // is silently dropped on Apply (the live preview shows it via deltaMacro,
        // but the committed stack wouldn't) — the exact "applied but preview
        // changed" class of bug. Reconstruct what the per-field cards represent;
        // if it doesn't match the delta, a field is uncaptured: discard the
        // partial cards and commit ONE complete card carrying the full delta —
        // no drop, and no double-apply (which a residual card would cause since
        // additive fields would then be counted twice). Warn so the missing
        // field gets its own per-field entry above.
        if (result.isNotEmpty()) {
            val captured = result.fold(UserMacro()) { acc, (_, single) -> acc.mergeWith(single) }
            // Compare against m folded the same way (UserMacro().mergeWith(m)) so
            // representation-normalizing fields — notably the LUT in-flight edit,
            // which mergeWith commits into slot 1/2 — don't false-trip the net.
            if (captured != UserMacro().mergeWith(m)) {
                android.util.Log.w(
                    "RawAdjustmentPanel",
                    "buildIndividualCards($tab): edited field(s) not captured by any card — " +
                        "committing one bundled '$tab' card so the edit isn't dropped. " +
                        "Add the field to buildIndividualCards for a granular card.",
                )
                result.clear()
                result += tab to m
            }
        }
        // Fallback: if nothing was decomposed (e.g. unknown tab or all defaults), emit one card
        // carrying the full delta so the Apply is never silently dropped.
        if (result.isEmpty()) result += tab to m
        return result
    }

    // Auto-apply: a slider move on a non-mask tab rebuilds that tab's granular
    // cards from the new values and replaces them in the stack immediately —
    // no Apply press, and the value persists (read back via composeTabMacro).
    fun onLiveChange(newMacro: UserMacro) {
        onReplaceTabCards(selectedTab, buildIndividualCards(selectedTab, newMacro))
    }

    // ── Mask-tab only (legacy paint → Apply / Cancel) ──────────────────────
    fun applyAndReturn() {
        val cards = buildIndividualCards(selectedTab, macro)
        // Emit one onApplyAction call per individual parameter card. Each call goes through
        // the same RawEditorContent handler (addAction) so each becomes its own stack entry.
        cards.forEach { (label, singleMacro) ->
            onApplyAction(label, selectedTab, singleMacro)
        }
        // The new edited card replaces the loaded original — discard
        // the restore reference. Without this, a future Cancel from a
        // different action's load would resurrect a stale ghost card.
        loadedOriginal = null
        pendingTab  = null
        selectedTab = TAB_ACTIONS
    }

    fun cancelAndReturn() {
        // If the user reached this tab by tapping a card in Actions,
        // re-insert that card at its original index so Cancel =
        // "back out, keep what was there". Manual-Apply flow has
        // loadedOriginal == null so this branch is a no-op.
        loadedOriginal?.let { lo -> onRestoreAction(lo.index, lo.action) }
        loadedOriginal = null
        onCancelAction()
        pendingTab  = null
        selectedTab = TAB_ACTIONS
    }

    Column(modifier = modifier) {
        // On-demand segmentation: when the user lands on a tab whose features need
        // masks (Mask Layers, local Gradient subject/background targets), kick off
        // the ONNX chain so masks are ready by the time they tap. No-op cost on
        // every other tab. (Segmentation no longer runs eagerly at open — that was
        // the OOM driver.)
        androidx.compose.runtime.LaunchedEffect(selectedTab) {
            // Mask Layers + Gradient (subject/bg targets) always need masks.
            // Effects (Bokeh) + Vignette (subject/bg) also host mask-gated
            // controls that are greyed until detection completes — kick it off
            // on entry so those controls can ungrey. Cached after the first run,
            // so revisiting any tab is instant (no recompute).
            if (selectedTab == TAB_MASK_LAYERS || selectedTab == TAB_MASKS_LOCAL ||
                selectedTab == TAB_EFFECTS || selectedTab == TAB_VIGNETTE_PANE) {
                onSegmentationNeeded()
            }
        }
        // Find the row position of the currently-selected global TAB_* index. If the
        // selected tab was filtered out (e.g. LIGHT on non-RAW), default to row 0.
        val selectedRowIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)
        // Per-tab "modified" dots (Lightroom pattern): a tab is marked when at
        // least one VISIBLE user card lives on it, so which sections are
        // non-default is readable at a glance without opening each tab.
        // Workspace-default cards are setup, not edits; eye-off cards don't
        // affect the render — both excluded. AE cards render on the Tone tab
        // regardless of the tabIndex they were committed with.
        // NOT remember(actions): the backing SnapshotStateList mutates in place,
        // so the instance key never changes and remember would cache forever.
        // The recompute is a scan of a handful of cards — free.
        val modifiedTabs = actions.asSequence()
            .filter { it.id != RawAction.ORIGINAL_ID && !it.isWorkspaceDefault && it.isVisible }
            .map { if (it.isAutoExposure) TAB_TONE_COLOR else it.tabIndex }
            .toSet()
        ScrollableTabRow(
            selectedTabIndex = selectedRowIndex,
            modifier  = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
        ) {
            tabs.forEach { (tabIndex, title) ->
                // Actions tab is always accessible; all others lock when another tab has a pending edit
                val isLocked = pendingTab != null && pendingTab != tabIndex && tabIndex != TAB_ACTIONS
                val unwired = !EditionCapabilities.isRawTabWired(tabIndex)
                Tab(
                    selected = selectedTab == tabIndex,
                    onClick  = { if (!isLocked && !unwired) selectedTab = tabIndex },
                    enabled  = !isLocked && !unwired,
                    text     = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, modifier = if (unwired) Modifier.alpha(0.38f) else Modifier)
                            if (tabIndex in modifiedTabs) {
                                Spacer(Modifier.size(4.dp))
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(50),
                                        )
                                )
                            }
                        }
                    },
                    modifier = Modifier.alpha(if (isLocked) 0.38f else 1f),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Auto-apply: non-mask tabs read their slider values from the
            // committed cards (composeTabMacro) and write live via onLiveChange.
            // The Mask tab alone keeps the in-flight `macro` (deltaMacro) + Apply.
            val liveMacro = if (isLiveTab(selectedTab)) composeTabMacro(selectedTab) else macro
            // TAB_CURVES_LUT gets a full-size non-scrolling canvas. Curves now
            // auto-apply like every other tab (no one-shot lock / Apply banner).
            if (selectedTab == TAB_CURVES_LUT) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(currentScrollState),
                ) {
                    // Embed the curves editor inline; it sizes itself to the available width.
                    RawToneCurvesTab(
                        macro           = liveMacro,
                        onMacroChange   = ::onLiveChange,
                        previewBitmap   = previewBitmap,
                        gradedHistogram = gradedHistogram,
                        modifier        = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    TabResetButton(
                        RawTabId.Curves, liveMacro, ::onLiveChange,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(currentScrollState),
                ) {
                    when (selectedTab) {
                        // ── LUT 1 / LUT 2: one cube per slot, applied once ───────
                        // RawLutTab is slot-agnostic (it edits "the active LUT" via
                        // lutCubeUri/lutIntensity); we shim those to the slot's
                        // committed value (or the in-flight edit while picking) and
                        // remap writes back to the slot via lutSlot+lutEdited.
                        TAB_LUT1, TAB_LUT2 -> {
                            val slot = if (selectedTab == TAB_LUT2) 2 else 1
                            val editingThisSlot = liveMacro.lutEdited && liveMacro.lutSlot == slot
                            val slotUri = when {
                                editingThisSlot -> liveMacro.lutCubeUri
                                slot == 2       -> liveMacro.lut2CubeUri
                                else            -> liveMacro.lut1CubeUri
                            }
                            val slotIntensity = when {
                                editingThisSlot -> liveMacro.lutIntensity
                                slot == 2       -> liveMacro.lut2Intensity
                                else            -> liveMacro.lut1Intensity
                            }
                            RawLutTab(
                                macro = liveMacro.copy(lutCubeUri = slotUri, lutIntensity = slotIntensity),
                                onMacroChange = { edited ->
                                    onLiveChange(
                                        liveMacro.copy(
                                            lutSlot      = slot,
                                            lutEdited    = true,
                                            lutCubeUri   = edited.lutCubeUri,
                                            lutIntensity = edited.lutIntensity,
                                        )
                                    )
                                },
                                modifier         = contentModifier,
                                subjectMaskReady = segmentationMasks != null,
                                showPicker       = true,
                                showFinishing    = false,
                                onSaveEditAsLut  = onSaveEditAsLut,
                            )
                            // Reset = clear this slot. Goes through the same slot
                            // shim as an edit: lutEdited + empty cube -> replaceTabCards
                            // removes the slot's card (see RawEditorComponent).
                            ResetPill(
                                text = "Clear LUT $slot",
                                onClick = {
                                    onLiveChange(
                                        liveMacro.copy(
                                            lutSlot      = slot,
                                            lutEdited    = true,
                                            lutCubeUri   = "",
                                            lutIntensity = 1f,
                                        )
                                    )
                                },
                                modifier = contentModifier,
                            )
                        }
                        // ── LUT ADJ: the LUT-adjustment finishing trims (CLAHE,
                        //    zone WB, skintone, bloom, dehaze) — moved out of the
                        //    Curves tab into their own tab. ─────────────────────
                        TAB_LUT_ADJ -> {
                            RawLutTab(
                                macro            = liveMacro,
                                onMacroChange    = ::onLiveChange,
                                modifier         = contentModifier,
                                subjectMaskReady = segmentationMasks != null,
                                showPicker       = false,
                                showFinishing    = true,
                            )
                        }
                        // ── Tab 0: Tone & Color (Light + Tonemap) ────────────────
                        TAB_TONE_COLOR -> {
                            RawLightTab(
                                macro         = liveMacro,
                                onMacroChange = { updated ->
                                    // When protection slider changes, notify caller to
                                    // re-run AE with new protection level.
                                    if (updated.aeSubjectProtection != liveMacro.aeSubjectProtection) {
                                        onLightAeProtectionChange(updated.aeSubjectProtection)
                                    } else {
                                        onLiveChange(updated)
                                    }
                                },
                                onAuto        = onLightAuto,
                                onAutoOff     = onLightAutoOff,
                                onBasicAuto   = onLightBasicAuto,
                                autoEnabled   = lightAutoEnabled,
                                aeActive      = lightAeActive,
                                aeLocked      = lightAeLocked,
                                // Smart Bright is now a live slider like any other —
                                // read from the committed card, never locked.
                                smartBright       = liveMacro.smartBright,
                                smartBrightLocked = false,
                                isNonRawSource    = isNonRawSource,
                                modifier      = contentModifier,
                            )
                            Spacer(Modifier.height(8.dp))
                            RawTonemapTab(
                                macro         = liveMacro,
                                onMacroChange = ::onLiveChange,
                                modifier      = contentModifier,
                            )
                        }

                        // ── Tab 1: Color Tools ───────────────────────────────────
                        TAB_COLOR_TOOLS -> RawColorTab(
                            macro        = liveMacro,
                            onMacroChange = ::onLiveChange,
                            asShotKelvin = asShotKelvin,
                            isNonRawSource = isNonRawSource,
                            sceneAutoEnhanceEnabled  = sceneAutoEnhanceEnabled,
                            onSceneAutoEnhanceChange = onSceneAutoEnhanceChange,
                            previewBitmap = previewBitmap,
                            modifier     = contentModifier,
                        )

                        // ── Vignette tab (split out of the old Local tab) ─────────
                        TAB_VIGNETTE_PANE -> {
                            RawVignetteTab(
                                macro                      = liveMacro,
                                onMacroChange              = ::onLiveChange,
                                segmentationMasks          = segmentationMasks,
                                isVignetteCenterMode       = isVignetteCenterMode,
                                onVignetteCenterModeChange = onVignetteCenterModeChange,
                                onSegmentationNeeded       = onSegmentationNeeded,
                                subjectSegBusy             = subjectSegBusy,
                                modifier                   = contentModifier,
                            )
                        }
                        // ── Gradient tab (the renamed Local tab — gradient only) ──
                        TAB_MASKS_LOCAL -> {
                            RawGradientTab(
                                macro             = liveMacro,
                                onMacroChange     = ::onLiveChange,
                                segmentationMasks = segmentationMasks,
                                subjectSegBusy    = subjectSegBusy,
                                modifier          = contentModifier,
                            )
                        }
                        // ── Mask: brush-mask painting + per-mask adjustments
                        //    (split out of the Local tab). ──────────────────────
                        TAB_MASK_LAYERS -> {
                            RawMaskTab(
                                macro                = macro,
                                onMacroChange        = ::onMacroChangeTracked,
                                sharpSpread          = sharpSpread,
                                onSharpSpreadChange  = onSharpSpreadChange,
                                onFillSharp          = onFillSharp,
                                showOverlay          = showMaskOverlay,
                                onShowOverlayChange  = onShowMaskOverlayChange,
                                brushMode            = brushMode,
                                onBrushModeChange    = onBrushModeChange,
                                brushSize            = brushSize,
                                onBrushSize          = onBrushSize,
                                brushIntensity       = brushIntensity,
                                onBrushIntensity     = onBrushIntensity,
                                brushFeather         = brushFeather,
                                onBrushFeather       = onBrushFeather,
                                colorTolerance       = colorTolerance,
                                onColorToleranceChange = onColorToleranceChange,
                                colorRange           = colorRange,
                                onColorRangeChange   = onColorRangeChange,
                                colorFeather         = colorFeather,
                                onColorFeatherChange = onColorFeatherChange,
                                colorSampleArgb      = colorSampleArgb,
                                onClearColorSamples  = onClearColorSamples,
                                chromaSubtractMode   = chromaSubtractMode,
                                hasMask              = hasMask,
                                onClearMask          = onClearMask,
                                segmentationMasks    = segmentationMasks,
                                onFillSubject        = onFillSubject,
                                onFillBackground     = onFillBackground,
                                onRemoveSubject      = onRemoveSubject,
                                onRemoveBackground   = onRemoveBackground,
                                onInvertMask         = onInvertMask,
                                hasMulticlass        = hasMulticlass,
                                onFillHair           = onFillHair,
                                onFillBodySkin       = onFillBodySkin,
                                onFillFaceSkin       = onFillFaceSkin,
                                onFillClothes        = onFillClothes,
                                onRemoveHair         = onRemoveHair,
                                onRemoveBodySkin     = onRemoveBodySkin,
                                onRemoveFaceSkin     = onRemoveFaceSkin,
                                onRemoveClothes      = onRemoveClothes,
                                hasCityscapes        = hasCityscapes,
                                onFillBuildingWall   = onFillBuildingWall,
                                onFillVegetation     = onFillVegetation,
                                onFillTerrain        = onFillTerrain,
                                onFillSky            = onFillSky,
                                onRemoveBuildingWall = onRemoveBuildingWall,
                                onRemoveVegetation   = onRemoveVegetation,
                                onRemoveTerrain      = onRemoveTerrain,
                                onRemoveSky          = onRemoveSky,
                                includedClasses      = includedMaskClasses,
                                primaryMaskClass     = primaryMaskClass,
                                primaryIsLuma        = primaryIsLuma,
                                primaryIsChroma      = primaryIsChroma,
                                onAddLuma            = onAddLuma,
                                onRemoveLuma         = onRemoveLuma,
                                onAddChroma          = onAddChroma,
                                onRemoveChroma       = onRemoveChroma,
                                isMulticlassLoading  = isMulticlassLoading,
                                isCityscapesLoading  = isCityscapesLoading,
                                modifier             = contentModifier,
                            )
                        }

                        // ── Tab 4: Effects ───────────────────────────────────────
                        TAB_EFFECTS -> RawEffectsTab(
                            macro         = liveMacro,
                            onMacroChange = ::onLiveChange,
                            onSegmentationNeeded = onSegmentationNeeded,
                            subjectSegBusy = subjectSegBusy,
                            subjectDetected = subjectDetected,
                            imageLongSide = imageLongSide,
                            isLensFlareMoveMode = isLensFlareMoveMode,
                            onLensFlareMoveModeChange = onLensFlareMoveModeChange,
                            modifier      = contentModifier,
                        )

                        // ── Tab 5: Texture / Grain ───────────────────────────────
                        TAB_TEXTURE_GRAIN -> RawDetailTab(
                            macro         = liveMacro,
                            onMacroChange = ::onLiveChange,
                            modifier      = contentModifier,
                        )

                        // ── Tab 6: Actions ───────────────────────────────────────
                        TAB_ACTIONS -> {
                            RawActionsTab(
                                actions        = actions,
                                presets        = presets,
                                onLoad         = { action ->
                                    // Tap-to-edit a committed card → jump to its tab.
                                    //
                                    // AUTO EXPO cards are LOCKED — a computed result
                                    // (per-segment stretch + exposure solver), not a
                                    // hand-tuned slider set; only delete is allowed.
                                    //
                                    // Non-mask cards now auto-apply, so editing is
                                    // just "open the tab": its sliders already read
                                    // back this card's value via composeTabMacro and
                                    // any move live-replaces it. No delete/lift, no
                                    // Apply button.
                                    //
                                    // Mask cards keep the legacy load: restore the
                                    // painted PNG + sliders into the in-flight macro
                                    // and arm the Mask tab's Apply/Cancel bar.
                                    if (!action.isAutoExposure) {
                                        if (action.maskPath != null || action.tabIndex == TAB_MASK_LAYERS) {
                                            // Save the original card so Cancel can restore it,
                                            // then delete it from the stack so Apply doesn't
                                            // create a duplicate.
                                            val idx = actions.indexOfFirst { it.id == action.id }
                                            if (idx >= 0) {
                                                loadedOriginal = LoadedOriginal(idx, action)
                                                onDeleteAction(action.id, true)
                                            }
                                            onLoadAction(action)
                                            pendingTab = action.tabIndex
                                        }
                                        selectedTab = action.tabIndex
                                    }
                                },
                                onEyeToggle    = onEyeToggleAction,
                                onDelete       = onDeleteAction,
                                onToggleLock   = onToggleLockAction,
                                onExport       = onExportActions,
                                onImport       = onImportActions,
                                onCopySettings   = onCopySettings,
                                onPasteSettings  = onPasteSettings,
                                canPasteSettings = canPasteSettings,
                                onApplyPrevious  = onApplyPrevious,
                                canApplyPrevious = canApplyPrevious,
                                onSavePreset   = onSavePreset,
                                onLoadPreset   = onLoadPreset,
                                onDeletePreset = onDeletePreset,
                                onExportDebugMap = onExportDebugMap,
                                modifier       = contentModifier,
                            )
                        }
                    }
                }
            }
        }

        // Bottom button area
        // Note: Heal tab moved to RawExportScreen — its Cancel/Apply bar is no longer here.
        when {
            selectedTab == TAB_ACTIONS -> {
                Button(
                    onClick  = onExportToEditor,
                    enabled  = isFullResReady && !isFullResProcessing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (isFullResProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        text = if (isFullResProcessing)
                            stringResource(R.string.raw_full_res_processing)
                        else
                            stringResource(R.string.raw_apply_to_editor)
                    )
                }
            }
            pendingTab != null -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick  = ::cancelAndReturn,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.raw_cancel)) }
                    Button(
                        onClick  = ::applyAndReturn,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.raw_apply)) }
                }
            }
            else -> {
                // Reserve the same height as the Cancel/Apply row so the graph
                // canvas (Tone Curves) doesn't shift when buttons appear.
                Spacer(Modifier.height(56.dp))
            }
        }
    }
}

/**
 * Full-area banner shown when a one-time-only tab (RAW Light, LUT, Tone Curves) already
 * has a committed card in the actions stack. Replaces the tab content entirely — no sliders
 * are rendered underneath. The user must go to the Actions tab and tap that card to re-edit.
 */
@Composable
private fun OneTimeOnlyBanner(tabName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text  = "$tabName can only be applied once",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text  = "A $tabName adjustment is already registered in the Actions stack. " +
                            "Go to the Actions tab and tap its card to edit it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}
