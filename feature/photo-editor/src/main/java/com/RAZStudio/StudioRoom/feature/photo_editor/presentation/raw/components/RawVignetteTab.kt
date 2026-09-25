/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.Icons
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.CenterFocusStrong
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedChip
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.SegmentTarget
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation.RawSegmentationMasks

@Composable
internal fun RawVignetteTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    segmentationMasks: RawSegmentationMasks?,
    isVignetteCenterMode: Boolean,
    onVignetteCenterModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // Subject/Background vignette targeting is mask-gated (subjectGate blocks
    // it until the mask uploads). Segmentation is lazy, so picking a
    // Subject/Background target must kick off the ONNX chain. Idempotent.
    onSegmentationNeeded: () -> Unit = {},
    // True while subject detection runs — greys out the Subject/Background
    // targets (All stays available) until the mask is ready.
    subjectSegBusy: Boolean = false,
) {
    Column(modifier = modifier) {

        // ── Center point touch button — placed at the top so the user can ────
        // park their finger on it before tweaking Amount/Intensity/Feather.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isVignetteCenterMode) {
                FilledIconButton(
                    onClick = { onVignetteCenterModeChange(false) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                OutlinedIconButton(
                    onClick = { onVignetteCenterModeChange(true) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (macro.vignetteInvert) {
                FilledIconButton(
                    onClick = { onMacroChange(macro.copy(vignetteInvert = false)) },
                    modifier = Modifier.size(40.dp),
                ) { Text("I") }
            } else {
                OutlinedIconButton(
                    onClick = { onMacroChange(macro.copy(vignetteInvert = true)) },
                    modifier = Modifier.size(40.dp),
                ) { Text("I") }
            }
            Column {
                Text(
                    text = stringResource(R.string.raw_vignette_center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isVignetteCenterMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (macro.vignetteInvert) "Center falloff"
                           else if (isVignetteCenterMode)
                        stringResource(R.string.raw_vignette_center_hint_active)
                    else
                        stringResource(R.string.raw_vignette_center_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // ── Amount ────────────────────────────────────────────────────────────
        // First-time non-zero edit snaps the vignette center to the subject
        // centroid when segmentation is available. The user can drag away via
        // 'Move center' from that point on; we don't re-snap on subsequent
        // amount changes (handled by `vignetteCenterAutoSnapped` sentinel).
        RawSliderRow(
            label = stringResource(R.string.raw_vignette_amount),
            value = macro.vignetteAmount,
            valueRange = -100f..100f,
            onValueChange = { newAmount ->
                val wasZero = macro.vignetteAmount == 0f
                val nowNonZero = newAmount != 0f
                if (wasZero && nowNonZero && !macro.vignetteCenterAutoSnapped) {
                    // One-shot snap. Center coords are normalized [0, 1];
                    // segmentation provides the subject centroid in the same
                    // space. When no mask is available the default (0.5, 0.5)
                    // is preserved. Also default to "Smart Vignette" =
                    // subject-protected when a mask is present.
                    val sc = segmentationMasks?.subjectCenterNormalized
                    val defaultSeg = if (segmentationMasks != null)
                        SegmentTarget.Background else macro.vignetteSegmentation
                    onMacroChange(
                        macro.copy(
                            vignetteAmount = newAmount,
                            vignetteCenterX = sc?.first  ?: macro.vignetteCenterX,
                            vignetteCenterY = sc?.second ?: macro.vignetteCenterY,
                            vignetteCenterAutoSnapped = true,
                            vignetteSegmentation = defaultSeg,
                        ),
                    )
                } else {
                    onMacroChange(macro.copy(vignetteAmount = newAmount))
                }
            },
        )
        Spacer(Modifier.height(4.dp))

        // ── Intensity ─────────────────────────────────────────────────────────
        // Range 0..1.5: existing 100 baseline still maps to 1.0 (= 100 in the
        // display label), 100→150 adds extra darkening/lightening for cases
        // where 100 wasn't strong enough. Saved presets at 1.0 keep their
        // existing brightness.
        RawSliderRow(
            label = stringResource(R.string.raw_vignette_intensity),
            value = macro.vignetteIntensity,
            valueRange = 0f..1.5f,
            onValueChange = { onMacroChange(macro.copy(vignetteIntensity = it)) },
            displayValue = "${(macro.vignetteIntensity * 100).toInt()}",
        )
        Spacer(Modifier.height(4.dp))

        // ── Feather ───────────────────────────────────────────────────────────
        // Display is inverted: slider right = more feather (soft edge), stored value is 1-display
        RawSliderRow(
            label = stringResource(R.string.raw_vignette_feather),
            value = 1f - macro.vignetteFeather,
            valueRange = 0f..1f,
            onValueChange = { onMacroChange(macro.copy(vignetteFeather = 1f - it)) },
            displayValue = "${((1f - macro.vignetteFeather) * 100).toInt()}",
        )

        // ── Smart Vignette (subject-aware) ─────────────────────────────
        // Renamed from the older "Apply to" chip group. Toggle replaces
        // chips for a clearer Snapseed-style affordance:
        //   ON  → vignette darkens/lightens only the BACKGROUND, subject
        //         stays at its original brightness (Snapseed Face Spotlight
        //         spirit, reusing the existing subject mask).
        //   OFF → applies to the whole frame (legacy "All" behaviour).
        if (segmentationMasks != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Apply To — 3-way segmentation target, mirroring the Gradient tab.
            // (Replaces the old binary "Smart Vignette" switch, which could only
            // reach All/Background and never Subject. vignetteSegmentation is a
            // SegmentTarget and the shader already gates via subjectGate(uVigEffect),
            // so all three values are honoured with no native change.)
            Text(
                text = "Apply To",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    SegmentTarget.All        to stringResource(R.string.raw_segment_all),
                    SegmentTarget.Subject    to stringResource(R.string.raw_segment_subject),
                    SegmentTarget.Background to stringResource(R.string.raw_segment_background),
                ).forEach { (target, label) ->
                    // Subject/Background need the mask, so grey them while
                    // detection runs; All is unconditional.
                    val chipEnabled = target == SegmentTarget.All || !subjectSegBusy
                    EnhancedChip(
                        selected = macro.vignetteSegmentation == target,
                        onClick  = if (chipEnabled) {
                            {
                                // Subject/Background targets need the mask —
                                // start segmentation now so the vignette isn't
                                // stuck inert (subjectGate blocks it until the
                                // mask loads).
                                if (target != SegmentTarget.All) onSegmentationNeeded()
                                onMacroChange(macro.copy(vignetteSegmentation = target))
                            }
                        } else null,
                        modifier = Modifier.alpha(if (chipEnabled) 1f else 0.38f),
                        label    = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        contentPadding         = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        selectedColor          = MaterialTheme.colorScheme.primaryContainer,
                        selectedContentColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (subjectSegBusy) {
                Text(
                    text = "Detecting subject…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        TabResetButton(RawTabId.Vignette, macro, onMacroChange)
    }
}
