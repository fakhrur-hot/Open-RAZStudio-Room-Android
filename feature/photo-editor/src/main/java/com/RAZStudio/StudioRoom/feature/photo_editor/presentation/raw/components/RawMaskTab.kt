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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Delete
import com.RAZStudio.StudioRoom.core.resources.icons.Draw
import com.RAZStudio.StudioRoom.core.resources.icons.Eraser
import com.RAZStudio.StudioRoom.core.resources.icons.AutoAwesome
import com.RAZStudio.StudioRoom.core.resources.icons.Close
import com.RAZStudio.StudioRoom.core.resources.icons.Contrast
import com.RAZStudio.StudioRoom.core.resources.icons.Face5
import com.RAZStudio.StudioRoom.core.resources.icons.Landscape
import com.RAZStudio.StudioRoom.core.resources.icons.Palette
import com.RAZStudio.StudioRoom.core.resources.icons.Person
import com.RAZStudio.StudioRoom.core.resources.icons.SelectInverse
import com.RAZStudio.StudioRoom.core.resources.icons.Visibility
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskBrushMode
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.MaskClass
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks
import kotlin.math.roundToInt

// Active-state color for selection buttons. Matches the mask overlay color on
// the canvas (see RawEditorContent.kt: `ColorFilter.tint(Color.Blue, …)`) so the
// button and the painted region read as the same "mask" hue at a glance.
private val MaskAccent = Color(0xFF0000FF)
private val MaskBorder = Color(0xFF444444)

/**
 * A single cell in the selection grid.
 *
 * [isPersistent] — stays mask-blue while [isSelected] is true (Draw / Erase toggle behaviour).
 * Otherwise flashes mask-blue only while the finger is physically down (momentary action buttons).
 */
/**
 * Two-pane split button for class-mask selection. Left half is "Select"
 * (OR this class into the current mask). Right half is "Remove" (subtract
 * this class from the current mask).
 *
 * Visual state:
 *   • Selected = true   → left half painted blue (MaskAccent), inner state
 *                         persists across re-renders so user sees which
 *                         classes are currently in the mask.
 *   • Selected = false  → left half outlined like a regular SelectionButton.
 *   • Right half is always thin red-tinted (MaskRemoveAccent) when enabled,
 *     dimmed when disabled.
 *
 * Tapping the LEFT half: calls [onSelect]. If already selected, this is
 * still useful (re-applies the fill to refresh the mask after another
 * class was added; common when stacking Sky + Buildings).
 *
 * Tapping the RIGHT half: calls [onRemove]. Inert when [hasMask] is false.
 */
@Composable
private fun SplitClassButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    enabled: Boolean,
    hasMask: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    isPrime: Boolean = false,
) {
    val removeAccent = Color(0xFFCF3B3B)
    Row(
        modifier = modifier
            .height(72.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaskBorder, RoundedCornerShape(12.dp)),
    ) {
        // Left half — Select / Enable
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (isSelected) MaskAccent else Color.Transparent)
                .clickable(enabled = enabled, onClick = onSelect),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = label,
                    tint               = Color.White,
                    modifier           = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text      = label,
                    color     = Color.White,
                    style     = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines  = 1,
                )
            }
            // Prime indicator — small "★" badge in top-start corner.
            if (isPrime) {
                Text(
                    text     = "★",
                    color    = Color.White,
                    style    = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, top = 3.dp),
                )
            }
        }
        // Right half — Remove
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .background(
                    if (hasMask && enabled) removeAccent.copy(alpha = 0.30f)
                    else Color.Transparent,
                )
                .clickable(enabled = enabled && hasMask, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Rounded.Close,
                contentDescription = "Remove $label",
                tint               = if (hasMask && enabled) Color.White
                                     else Color.White.copy(alpha = 0.4f),
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SelectionButton(
    label: String,
    icon: ImageVector,
    isPersistent: Boolean,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isActive = if (isPersistent) isSelected else isPressed

    Box(
        modifier = modifier
            .height(72.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) MaskAccent else Color.Transparent)
            .then(
                if (!isActive) Modifier.border(1.dp, MaskBorder, RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                enabled           = enabled,
                onClick           = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = Color.White,
                modifier           = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text      = label,
                color     = Color.White,
                style     = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines  = 1,
            )
        }
    }
}

/**
 * Mask tab content for the RAW editor adjustment panel.
 *
 * Layout:
 *   1. Selection grid (2 × 3):
 *      Row 1 — Draw (persistent toggle), Erase (persistent toggle), Invert (momentary)
 *      Row 2 — Select Subject (U2Net, momentary), Select Background (U2Net, momentary),
 *              Clear (momentary)
 *   2. Brush controls — Size / Intensity / Feather sliders (pure UI state, not in UserMacro).
 *   3. Adjustment sliders — Brightness / Contrast / Temperature / Tint / Saturation stored
 *      in [UserMacro]. Changing any slider sets [pendingTab] and enables Apply / Cancel.
 *
 * The canvas painting is handled by RawEditorContent which intercepts single-finger drags
 * when [brushMode] is Draw or Erase and the Mask tab is active.
 */
@Composable
internal fun RawMaskTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    /**
     * Whether the mask overlay is currently being shown on the canvas.
     * When false, Draw / Erase / Tap-Select buttons are disabled and
     * the canvas behaves as a normal pan/pinch surface. Toggled by the
     * "Show" button — independent from the Mask tab being selected.
     */
    showOverlay: Boolean,
    onShowOverlayChange: (Boolean) -> Unit,
    brushMode: MaskBrushMode,
    onBrushModeChange: (MaskBrushMode) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    brushIntensity: Float,
    onBrushIntensity: (Float) -> Unit,
    brushFeather: Float,
    onBrushFeather: (Float) -> Unit,
    // Color-range ("Select Color") tool. colorTolerance = Refine 0..100;
    // colorSampleArgb = last sampled colour (0 = none, drives the swatch).
    colorTolerance: Float = 50f,
    onColorToleranceChange: (Float) -> Unit = {},
    colorRange: Float = 60f,
    onColorRangeChange: (Float) -> Unit = {},
    colorFeather: Float = 30f,
    onColorFeatherChange: (Float) -> Unit = {},
    colorSampleArgb: Int = 0,
    onClearColorSamples: () -> Unit = {},
    /** When true, ColorSelect taps carve keyed colour out of the current mask. */
    chromaSubtractMode: Boolean = false,
    hasMask: Boolean,
    onClearMask: () -> Unit,
    segmentationMasks: RawSegmentationMasks?,
    onFillSubject: () -> Unit,
    onFillBackground: () -> Unit,
    onRemoveSubject: () -> Unit,
    onRemoveBackground: () -> Unit,
    onInvertMask: () -> Unit,
    /**
     * MediaPipe multiclass class-fill callbacks. Each fires when the
     * user taps the corresponding Select button. The host (RawEditor
     * Content) is responsible for resolving the per-class FloatArray
     * mask and pushing it through [fillFromSegmentation].
     *
     * `hasMulticlass` gates the button row's visibility — false until
     * MediaPipe inference finishes (or stays false forever if the
     * model isn't bundled).
     */
    /** True while MediaPipe inference is running — shows spinner, hides buttons. */
    isMulticlassLoading: Boolean = false,
    hasMulticlass: Boolean = false,
    onFillHair: () -> Unit = {},
    onFillBodySkin: () -> Unit = {},
    onFillFaceSkin: () -> Unit = {},
    onFillClothes: () -> Unit = {},
    onRemoveHair: () -> Unit = {},
    onRemoveBodySkin: () -> Unit = {},
    onRemoveFaceSkin: () -> Unit = {},
    onRemoveClothes: () -> Unit = {},
    /**
     * Cityscapes 4-class fill callbacks (SegFormer-B1 ONNX). Each fires
     * when the user taps the matching button. The host (RawEditorContent)
     * resolves the per-class FloatArray mask and pushes it through
     * fillFromSegmentation.
     *
     * hasCityscapes gates the new button row's enable-state — false until
     * SegFormer inference finishes (or stays false if the model is missing).
     */
    /** True while Cityscapes/SegFormer inference is running — shows spinner, hides buttons. */
    isCityscapesLoading: Boolean = false,
    hasCityscapes: Boolean = false,
    onFillBuildingWall: () -> Unit = {},
    onFillVegetation:   () -> Unit = {},
    onFillTerrain:      () -> Unit = {},
    onFillSky:          () -> Unit = {},
    onRemoveBuildingWall: () -> Unit = {},
    onRemoveVegetation:   () -> Unit = {},
    onRemoveTerrain:      () -> Unit = {},
    onRemoveSky:          () -> Unit = {},
    /**
     * M12.2c.5 — Sharp Edges momentary fill. Press = generate a new
     * subject mask using U2Net + Sobel edge-snap, dilated/eroded by
     * [sharpSpread] in [-1, +1]. The button is momentary: hollow when
     * idle, mask-blue while finger is down. State is the resulting
     * Bitmap, not a latching toggle.
     */
    sharpSpread: Float = 0f,
    onSharpSpreadChange: (Float) -> Unit = {},
    onFillSharp: () -> Unit = {},
    /**
     * Per-class inclusion set. Each entry marks that class's left half
     * of its split-button as "blue / included". Membership is updated
     * by the host: left-tap callbacks add, right-tap callbacks remove,
     * Clear empties the set.
     */
    includedClasses: Set<MaskClass> = emptySet(),
    /** The prime mask class — first one selected. Shows a star indicator on its button. */
    primaryMaskClass: MaskClass? = null,
    primaryIsLuma: Boolean = false,
    primaryIsChroma: Boolean = false,
    /**
     * Luma / Chroma Add+Remove. Host owns clear-vs-carve semantics so a
     * bitmap/object base can be carved by a range tool and vice versa
     * (see maskLumCombine 1/2). Defaults keep prior wipe-everything behaviour
     * only if the host forgets to wire them.
     */
    onAddLuma: () -> Unit = {},
    onRemoveLuma: () -> Unit = {},
    onAddChroma: () -> Unit = {},
    onRemoveChroma: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── Row 1: Show ───────────────────────────────────────────────────────────
        // Show toggles the blue mask overlay on the canvas.
        //   ON  → overlay visible; Draw and Erase become available.
        //   OFF → overlay hidden (canvas shows clean photo); Draw and Erase
        //         both forced off — you can't paint what you can't see.
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectionButton(
                label        = "Show",
                icon         = Icons.Rounded.Visibility,
                isPersistent = true,
                isSelected   = showOverlay,
                onClick      = {
                    val newVal = !showOverlay
                    onShowOverlayChange(newVal)
                    if (!newVal) onBrushModeChange(MaskBrushMode.None)
                },
                modifier = Modifier.weight(1f),
            )
            // Luma-only selections have no bitmap — still allow Invert (bakes
            // complement) and Clear (zeros maskLum*). Chroma is bitmap-backed.
            val lumaOnlyActive = macro.maskLumSpread > 0f
            val chromaOnlyActive = colorSampleArgb != 0
            val canEditSelection = hasMask || lumaOnlyActive || chromaOnlyActive
            SelectionButton(
                label        = stringResource(R.string.raw_mask_invert),
                icon         = Icons.Rounded.SelectInverse,
                isPersistent = false,
                enabled      = canEditSelection,
                onClick      = onInvertMask,
                modifier     = Modifier.weight(1f),
            )
            SelectionButton(
                label        = stringResource(R.string.raw_mask_clear),
                icon         = Icons.Rounded.Delete,
                isPersistent = false,
                enabled      = canEditSelection,
                onClick      = onClearMask,
                modifier     = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))

        // ── Row 2: Draw | Erase ───────────────────────────────────────────────────
        // Both require Show ON — grayed and non-interactive when overlay is hidden.
        //   Draw  → finger paints new mask pixels (blue overlay grows).
        //   Erase → finger removes mask pixels (blue overlay shrinks).
        // Tap the active button to deactivate (toggle off → MaskBrushMode.None).
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectionButton(
                label        = "Draw",
                icon         = Icons.Rounded.Draw,
                isPersistent = true,
                isSelected   = showOverlay && brushMode == MaskBrushMode.Draw,
                enabled      = showOverlay,
                onClick      = {
                    onBrushModeChange(
                        if (brushMode == MaskBrushMode.Draw) MaskBrushMode.None
                        else MaskBrushMode.Draw,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            SelectionButton(
                label        = stringResource(R.string.raw_mask_erase),
                icon         = Icons.Rounded.Eraser,
                isPersistent = true,
                isSelected   = showOverlay && brushMode == MaskBrushMode.Erase,
                enabled      = showOverlay,
                onClick      = {
                    onBrushModeChange(
                        if (brushMode == MaskBrushMode.Erase) MaskBrushMode.None
                        else MaskBrushMode.Erase,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Color (Chroma) + Luma range-select tools now live in the "Select
        // target object" dropdown below (top category, above the object classes).

        // ── Luminance-range controls — only while the Luma tool is active ───
        AnimatedVisibility(
            visible = showOverlay && (brushMode == MaskBrushMode.LumaSelect || primaryIsLuma),
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (macro.maskLumCombine == 2)
                        "Exclude this tone band from the current selection."
                    else if (macro.maskLumCombine == 3)
                        "Union this tone band with the current selection."
                    else
                        "Tone range. Feather keeps the edit halo-free.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RawSliderRow(
                    label         = "Level",
                    value         = macro.maskLumTarget * 100f,
                    valueRange    = 0f..100f,
                    onValueChange = { onMacroChange(macro.copy(maskLumTarget = it / 100f)) },
                    displayValue  = (macro.maskLumTarget * 100f).roundToInt().toString(),
                )
                RawSliderRow(
                    label         = "Range",
                    value         = macro.maskLumSpread * 100f,
                    valueRange    = 0f..100f,
                    onValueChange = { onMacroChange(macro.copy(maskLumSpread = (it / 100f).coerceAtLeast(0.001f))) },
                    displayValue  = (macro.maskLumSpread * 100f).roundToInt().toString(),
                )
                RawSliderRow(
                    label         = "Feather",
                    value         = macro.maskLumFeather * 100f,
                    valueRange    = 0f..100f,
                    onValueChange = { onMacroChange(macro.copy(maskLumFeather = it / 100f)) },
                    displayValue  = (macro.maskLumFeather * 100f).roundToInt().toString(),
                )
            }
        }

        // ── Color-range controls — only while the Color tool is active ──────
        AnimatedVisibility(
            visible = showOverlay && (brushMode == MaskBrushMode.ColorSelect || primaryIsChroma),
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (colorSampleArgb != 0) Color(colorSampleArgb) else Color.Transparent)
                            .border(1.dp, MaskBorder, RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = when {
                            chromaSubtractMode && colorSampleArgb == 0 ->
                                "Tap the photo to carve a colour"
                            chromaSubtractMode ->
                                "Tap more colours to carve"
                            colorSampleArgb == 0 ->
                                "Tap the photo to pick a colour"
                            else ->
                                "Tap more to add colours"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (colorSampleArgb != 0) {
                        TextButton(onClick = onClearColorSamples) {
                            Text(stringResource(R.string.raw_mask_clear), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                RawSliderRow(
                    label         = "Range",
                    value         = colorRange,
                    valueRange    = 0f..100f,
                    onValueChange = onColorRangeChange,
                    displayValue  = colorRange.roundToInt().toString(),
                )
                RawSliderRow(
                    label         = "Feather",
                    value         = colorFeather,
                    valueRange    = 0f..100f,
                    onValueChange = onColorFeatherChange,
                    displayValue  = colorFeather.roundToInt().toString(),
                )
                RawSliderRow(
                    label         = "Refine",
                    value         = colorTolerance,
                    valueRange    = 0f..100f,
                    onValueChange = onColorToleranceChange,
                    displayValue  = "+${colorTolerance.roundToInt()}",
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Detected-object targets (dropdown) ─────────────────────────────
        // Replaces the old per-object split-button grid. Segmentation runs
        // on-demand when this tab opens; while the models scan we show a
        // progress row, and only once scanning completes do we show a single
        // dropdown listing the classes ACTUALLY detected. Each item toggles
        // that class into (add / OR) or out of (subtract) the mask, so several
        // can be composited — the old additive + subtract behaviour, in one
        // control. Manual brush tools below still refine the result.
        Spacer(Modifier.height(6.dp))
        run {
            data class MaskTarget(
                val cls: MaskClass,
                val label: String,
                val icon: ImageVector,
                val onAdd: () -> Unit,
                val onRemove: () -> Unit,
            )
            val targets = buildList {
                if (segmentationMasks != null) {
                    add(MaskTarget(MaskClass.Subject, stringResource(R.string.raw_mask_subject),
                        Icons.Rounded.Person, onFillSubject, onRemoveSubject))
                    add(MaskTarget(MaskClass.Background, stringResource(R.string.raw_mask_background),
                        Icons.Outlined.Landscape, onFillBackground, onRemoveBackground))
                }
                if (hasMulticlass) {
                    add(MaskTarget(MaskClass.FaceSkin, "Face", Icons.Rounded.Face5, onFillFaceSkin, onRemoveFaceSkin))
                    add(MaskTarget(MaskClass.Hair, "Hair", Icons.Rounded.AutoAwesome, onFillHair, onRemoveHair))
                    add(MaskTarget(MaskClass.BodySkin, "Body", Icons.Rounded.Person, onFillBodySkin, onRemoveBodySkin))
                    add(MaskTarget(MaskClass.Clothes, "Clothes", Icons.Rounded.Palette, onFillClothes, onRemoveClothes))
                }
                if (hasCityscapes) {
                    add(MaskTarget(MaskClass.Sky, "Sky", Icons.Outlined.Landscape, onFillSky, onRemoveSky))
                    add(MaskTarget(MaskClass.Vegetation, "Plants", Icons.Rounded.AutoAwesome, onFillVegetation, onRemoveVegetation))
                    add(MaskTarget(MaskClass.Buildings, "Buildings", Icons.Rounded.Person, onFillBuildingWall, onRemoveBuildingWall))
                    add(MaskTarget(MaskClass.Terrain, "Terrain", Icons.Outlined.Landscape, onFillTerrain, onRemoveTerrain))
                }
            }
            // "Scanning" until the subject model AND any running class models finish.
            val scanning = segmentationMasks == null || isMulticlassLoading || isCityscapesLoading
            val lumaActive = macro.maskLumSpread > 0f
            val chromaActive = colorSampleArgb != 0
            // A "base" mask exists once anything is selected. When it does,
            // EVERY object row's Remove becomes active so its region can be
            // subtracted from the base (the base row's own Remove clears all,
            // handled by primaryMaskClass in the callbacks). Before any base
            // exists there's nothing to subtract from, so Remove stays inert.
            val anyMaskActive = hasMask || lumaActive || chromaActive ||
                includedClasses.isNotEmpty()

            // Reusable dropdown row with explicit Add + Remove actions.
            @Composable
            fun MaskMenuRow(
                label: String,
                icon: ImageVector,
                included: Boolean,
                addEnabled: Boolean,
                onAdd: () -> Unit,
                onRemove: () -> Unit,
                // Remove is enabled when this row is included (undo its own add)
                // OR whenever a base mask exists (subtract this region from it).
                removeEnabled: Boolean = included,
            ) {
                DropdownMenuItem(
                    text        = { Text(label) },
                    leadingIcon = {
                        Icon(
                            imageVector        = icon,
                            contentDescription = null,
                            tint = when {
                                included   -> MaskAccent
                                addEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                else       -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            },
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text  = "Add",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    included   -> MaterialTheme.colorScheme.onSurfaceVariant
                                    addEnabled -> MaskAccent
                                    else       -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = addEnabled && !included) { onAdd() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                            Text(
                                text  = "Remove",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (removeEnabled) Color(0xFFCF3B3B)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = removeEnabled) { onRemove() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    },
                    onClick = { }, // explicit Add/Remove carry the actions
                )
            }

            Text(
                text       = "Select target object",
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            var expanded by remember { mutableStateOf(false) }
            val objCount = targets.count { it.cls in includedClasses }
            val total = objCount + (if (lumaActive) 1 else 0) + (if (chromaActive) 1 else 0)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text      = if (total == 0) "Select target object" else "$total selected  ▾",
                        modifier  = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // ── Range selection: Luma + Chroma ──
                    // Same Add/Remove as object rows. Add = union; Remove = exclude.
                    // Sliders appear immediately (no extra enable button).
                    MaskMenuRow(
                        label = "Luma", icon = Icons.Rounded.Contrast,
                        included = lumaActive, addEnabled = !lumaActive,
                        onAdd = {
                            expanded = false
                            onShowOverlayChange(true)
                            onAddLuma()
                        },
                        // Enabled whenever ANY base exists so Luma can carve a
                        // subject/brush/chroma selection (not only undo itself).
                        onRemove = {
                            expanded = false
                            onRemoveLuma()
                        },
                        removeEnabled = anyMaskActive,
                    )
                    MaskMenuRow(
                        label = "Chroma", icon = Icons.Rounded.Palette,
                        included = chromaActive, addEnabled = !chromaActive,
                        onAdd = {
                            expanded = false
                            onShowOverlayChange(true)
                            onAddChroma()
                        },
                        onRemove = {
                            expanded = false
                            onRemoveChroma()
                        },
                        removeEnabled = anyMaskActive,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // ── Detected object classes ──
                    if (scanning) {
                        DropdownMenuItem(
                            enabled = false,
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaskAccent)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Detecting objects…", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = { },
                        )
                    } else {
                        targets.forEach { t ->
                            MaskMenuRow(
                                label = t.label, icon = t.icon,
                                included = t.cls in includedClasses, addEnabled = true,
                                onAdd = {
                                    expanded = false
                                    t.onAdd()
                                },
                                onRemove = {
                                    expanded = false
                                    t.onRemove()
                                },
                                // Any base mask present → allow subtracting this
                                // object's region from it (not just undoing an add).
                                removeEnabled = anyMaskActive,
                            )
                        }
                    }
                }
            }
        }

        // ── Brush controls — only while Draw or Erase is the active tool ────────
        // (per owner request: the Size/Intensity/Feather sliders are irrelevant
        // unless you're actually painting/erasing, so they stay hidden otherwise).
        if (showOverlay && (brushMode == MaskBrushMode.Draw || brushMode == MaskBrushMode.Erase)) {
            HorizontalDivider(
                color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                text       = stringResource(R.string.raw_mask_brush),
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RawSliderRow(
                label         = stringResource(R.string.raw_mask_brush_size),
                value         = brushSize,
                valueRange    = 5f..150f,
                onValueChange = onBrushSize,
                displayValue  = brushSize.roundToInt().toString(),
            )
            RawSliderRow(
                label         = stringResource(R.string.raw_mask_brush_intensity),
                value         = brushIntensity,
                valueRange    = 0f..1f,
                onValueChange = onBrushIntensity,
                displayValue  = "${(brushIntensity * 100).roundToInt()}%",
            )
            RawSliderRow(
                label         = stringResource(R.string.raw_mask_brush_feather),
                value         = brushFeather,
                valueRange    = 0f..1f,
                onValueChange = onBrushFeather,
                displayValue  = "${(brushFeather * 100).roundToInt()}%",
            )
        }

        // ── Adjustments — categorised ──────────────────────────────────────────
        //
        // Light  : Brightness, Contrast
        // Tone   : Highlights, Shadows, Whites, Blacks
        // Color  : Temperature, Tint, Saturation
        // Detail : Clarity
        // Bokeh  : Background Blur, Bokeh Balls, Glow/Bloom (mask-gated at save)
        //
        // Highlights/Shadows/Whites/Blacks now render live: the GL uber-shader
        // mask loop applies applyToneRegionsP per layer (uMaskHighlights/…),
        // mirrored in the CPU export (apply_macro.cpp) — so preview == export.
        HorizontalDivider(
            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            text       = "Light",
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_brightness),
            value         = macro.maskBrightness,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskBrightness = it)) },
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_contrast),
            value         = macro.maskContrast,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskContrast = it)) },
        )

        HorizontalDivider(
            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            text       = "Tone",
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RawSliderRow(
            label         = "Highlights",
            value         = macro.maskTone.highlights,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskTone = macro.maskTone.copy(highlights = it))) },
        )
        RawSliderRow(
            label         = "Shadows",
            value         = macro.maskTone.shadows,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskTone = macro.maskTone.copy(shadows = it))) },
        )
        RawSliderRow(
            label         = "Whites",
            value         = macro.maskTone.whites,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskTone = macro.maskTone.copy(whites = it))) },
        )
        RawSliderRow(
            label         = "Blacks",
            value         = macro.maskTone.blacks,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskTone = macro.maskTone.copy(blacks = it))) },
        )

        HorizontalDivider(
            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            text       = "Color",
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_temperature),
            value         = macro.maskTemperature.toFloat(),
            valueRange    = -2000f..2000f,
            onValueChange = { onMacroChange(macro.copy(maskTemperature = it.roundToInt())) },
            displayValue  = run {
                val k = macro.maskTemperature
                if (k > 0) "+${k}K" else "${k}K"
            },
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_tint),
            value         = macro.maskTint,
            valueRange    = -150f..150f,
            onValueChange = { onMacroChange(macro.copy(maskTint = it)) },
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_saturation),
            value         = macro.maskSaturation,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskSaturation = it)) },
        )

        // NOTE: a stray "Local Contrast (Mask-targeted)" block used to live here
        // with a global Clarity slider (macro.clarity) — which DUPLICATED the
        // real per-mask Clarity in the Detail section below (macro.maskClarity)
        // — and a global Ambiance slider (macro.ambiance) that isn't mask-
        // targeted (no per-mask ambiance exists in the engine), so it appeared
        // to "do nothing" in the mask context. Both removed 2026-08-26; use the
        // Detail → Clarity (per-mask) below, and the FX tab for global ambiance.

        HorizontalDivider(
            color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            text       = "Detail",
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_clarity),
            value         = macro.maskClarity,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskClarity = it)) },
        )
        RawSliderRow(
            label         = stringResource(R.string.raw_mask_sharpness),
            value         = macro.maskSharpness,
            valueRange    = -100f..100f,
            onValueChange = { onMacroChange(macro.copy(maskSharpness = it)) },
        )

        // Bokeh (Background Blur) moved to the FX tab. The painted mask layer
        // still gates where bokeh applies at bake time; the blur amount is now
        // set globally in FX.
        // Resets this mask's adjustments only (selection/lum-range kept).
        TabResetButton(RawTabId.Mask, macro, onMacroChange)
    }
}
