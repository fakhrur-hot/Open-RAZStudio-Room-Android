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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * Output color space for RAW export.
 *
 * [SRGB]         — IEC 61966-2-1, BT.709 primaries, sRGB transfer function. Universal compatibility.
 * [DISPLAY_P3]   — Apple/Google Display P3: DCI-P3 primaries with a D65 white point and the
 *                  sRGB piecewise transfer function. Matches modern Android OLED/AMOLED wide-gamut
 *                  panels and Android's [android.graphics.ColorSpace.Named.DISPLAY_P3] tag. ~25 %
 *                  larger gamut than sRGB; preview is only accurate on wide-gamut displays.
 * [PROPHOTO_RGB] — ROMM RGB / ProPhoto RGB, covers ~90 % of the human visible spectrum and
 *                  100 % of Pointer's Gamut. D50 white point, γ1.8 transfer function.
 *                  Ideal for archival and round-trip workflows; requires a wide-gamut-aware viewer.
 */
enum class RawColorSpace(val displayName: String) {
    SRGB("sRGB"),
    DISPLAY_P3("DCI-P3"),
    PROPHOTO_RGB("ProPhoto RGB"),
}
