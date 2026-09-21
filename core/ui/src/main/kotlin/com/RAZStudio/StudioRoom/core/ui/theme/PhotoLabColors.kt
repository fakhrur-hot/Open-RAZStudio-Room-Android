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

package com.RAZStudio.StudioRoom.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode

/**
 * Dark chrome for a photo editor. Surfaces stay near-neutral so they do not
 * color-cast the canvas. Accent is a low-chroma tungsten steel, not a
 * finance-app purple/green pair.
 */
object PhotoLabColors {
    val charcoal = Color(0xFF0C0C0E)
    val ink = Color(0xFF101012)
    val slate = Color(0xFF161618)
    val panel = Color(0xFF1C1C20)
    val raised = Color(0xFF242428)
    val hairline = Color(0x33FFFFFF)
    val steel = Color(0xFF8A9AAB)
    val tungsten = Color(0xFFC4B7A6)

    val letterboxWhite = Color(0xFFFFFFFF)
    val letterboxBlack = Color(0xFF000000)
    val letterboxGray18 = Color(0xFF777777)

    fun auroraBrush(amoled: Boolean): Brush {
        val deep = if (amoled) Color.Black else charcoal
        return Brush.radialGradient(
            colors = listOf(
                slate.copy(alpha = 0.9f),
                deep,
                Color(0xFF12141A),
            ),
            tileMode = TileMode.Clamp,
        )
    }
}

fun ColorScheme.photoEditSafe(
    isNightMode: Boolean,
    amoled: Boolean,
): ColorScheme {
    if (!isNightMode) return this
    val bg = if (amoled) Color.Black else PhotoLabColors.charcoal
    val surf = if (amoled) PhotoLabColors.ink else PhotoLabColors.slate
    val container = PhotoLabColors.panel
    return copy(
        background = bg,
        surface = surf,
        surfaceDim = bg,
        surfaceBright = PhotoLabColors.raised,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surf,
        surfaceContainer = container,
        surfaceContainerHigh = PhotoLabColors.raised,
        surfaceContainerHighest = PhotoLabColors.raised.blend(Color.White, 0.06f),
        surfaceVariant = PhotoLabColors.raised,
        inverseSurface = Color(0xFFE8E6E3),
        inverseOnSurface = PhotoLabColors.charcoal,
        outline = outline.blend(Color.White, 0.12f),
        outlineVariant = outlineVariant.blend(Color.White, 0.06f),
        primary = primary.blend(PhotoLabColors.steel, 0.45f),
        secondary = secondary.blend(PhotoLabColors.tungsten, 0.55f),
        tertiary = tertiary.blend(PhotoLabColors.steel, 0.55f),
        primaryContainer = primaryContainer.blend(container, 0.55f),
        secondaryContainer = secondaryContainer.blend(container, 0.55f),
        tertiaryContainer = tertiaryContainer.blend(container, 0.55f),
    )
}
