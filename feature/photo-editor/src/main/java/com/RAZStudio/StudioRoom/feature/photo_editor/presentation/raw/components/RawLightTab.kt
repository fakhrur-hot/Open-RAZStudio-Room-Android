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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import kotlin.math.roundToInt

@Composable
internal fun RawLightTab(
    macro: UserMacro,
    onMacroChange: (UserMacro) -> Unit,
    onAuto: () -> Unit = {},
    /** Called when AE is toggled OFF — caller should clear the AE delta. */
    onAutoOff: () -> Unit = {},
    /** Basic Auto — instant GLOBAL auto-brightness/levels, no subject detection. */
    onBasicAuto: () -> Unit = {},
    autoEnabled: Boolean = true,
    /** True once Auto Exposure has been applied. */
    aeActive: Boolean = false,
    /**
     * Locked AI Expose: when "Auto Expose on Open" is enabled in the workspace
     * selector, AE is force-baked on open. The checkbox is then shown ticked,
     * disabled and greyed — the user can't untick it here.
     */
    aeLocked: Boolean = false,
    /**
     * Smart Bright slider value [0..4] to display. When a Smart Bright card is
     * committed this is the card's value; otherwise it mirrors macro.smartBright.
     */
    smartBright: Float = 0f,
    /**
     * Locked Smart Bright: true while a Smart Bright action card is registered.
     * The slider is greyed and non-interactive until the user deletes that card.
     */
    smartBrightLocked: Boolean = false,
    /** True for JPEG/PNG/WebP/etc. Smart Bright is LibRaw auto-bright — it has
     *  no meaning without sensor data, so it's hidden for non-RAW sources
     *  (the slider would do nothing). */
    isNonRawSource: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !aeLocked && (autoEnabled || aeActive)) {
                    if (aeActive) onAutoOff() else onAuto()
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (aeLocked || autoEnabled || aeActive) {
                Checkbox(
                    // Locked → always ticked; otherwise reflects AE state.
                    checked = aeLocked || aeActive,
                    onCheckedChange = { checked ->
                        if (checked) onAuto() else onAutoOff()
                    },
                    // Disabled (greyed) when locked or while computing.
                    enabled = !aeLocked && (autoEnabled || aeActive),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(2.dp),
                    strokeWidth = 2.dp,
                )
            }
            Column {
                Text(
                    text = "AI Expose",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (aeLocked || autoEnabled || aeActive)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                when {
                    aeLocked -> Text(
                        text = "Locked by Auto Expose on Open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !autoEnabled && !aeActive -> Text(
                        text = "Detecting subject…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // ── Basic Auto ──────────────────────────────────────────────────────
        // Instant GLOBAL auto-brightness/levels. Unlike AI Expose (per-segment,
        // waits for subject masks), this runs a global percentile auto-tone
        // immediately and commits as the auto-exposure overlay. Disabled until
        // the source thumbnail is ready (same gate as AI Expose) and while AE
        // is locked by "Auto Expose on Open".
        // Lightroom pattern: Auto greys itself out once applied. An AE card is
        // already active (aeActive) → tapping again would just re-stack the same
        // solve; the AI Expose checkbox above is the way to remove it.
        androidx.compose.material3.TextButton(
            onClick = onBasicAuto,
            enabled = !aeLocked && !aeActive,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (aeActive) "Basic Auto — applied"
                else "Basic Auto — global brightness"
            )
        }
        // ── Smart Bright (RAW only) ─────────────────────────────────────────
        // Fractional LibRaw auto-bright. 0 = off; 100% = the practical ceiling
        // (30% of full AB), folded into exposure at flatten time. It's derived
        // from sensor data, so it's HIDDEN for non-RAW (JPEG/PNG/…) where it
        // would have no effect. Committed via buildIndividualCards; locked while
        // a "Tone · Smart Bright" card exists.
        // Also HIDDEN when "Auto Expose on Open" is selected (aeLocked): AI Expose
        // already governs global brightness, so a competing Smart Bright control
        // would be redundant/confusing.
        if (!isNonRawSource && !aeLocked) {
            Spacer(Modifier.height(4.dp))
            RawSliderRow(
                label = "Smart Bright",
                value = smartBright,
                valueRange = 0f..4f,
                enabled = !smartBrightLocked,
                onValueChange = { onMacroChange(macro.copy(smartBright = it)) },
                displayValue = if (smartBright <= 0f) "Off"
                               else "${(smartBright / 4f * 100f).roundToInt()}%",
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
