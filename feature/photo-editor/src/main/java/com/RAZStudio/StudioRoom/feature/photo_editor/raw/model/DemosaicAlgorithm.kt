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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * Demosaicing algorithm selector — locked to RAZAMaZE+LMMSE.
 *
 * userQual = -3: AMaZE (LibRaw user_qual=12) for detail regions,
 * LMMSE (custom Wiener-filter) for flat/saturated areas, blended
 * via a per-pixel contrast mask. See stage_a.cpp DualVNG section.
 */
enum class DemosaicAlgorithm(
    val userQual: Int,
    val displayName: String,
    val description: String,
) {
    RAZ_AMAZE_VNG(-3, "RAZAMaZE+LMMSE",
        "AMaZE for detail, LMMSE for flat / saturated areas — fewest false-colour artifacts"),
    ;

    companion object {
        val DEFAULT = RAZ_AMAZE_VNG

        /** Safe deserialisation — any legacy value maps to the only available algorithm. */
        fun fromName(name: String): DemosaicAlgorithm =
            runCatching { valueOf(name) }.getOrDefault(RAZ_AMAZE_VNG)
    }
}
