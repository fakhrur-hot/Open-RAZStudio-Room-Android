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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.domain

import android.net.Uri

/**
 * Represents the state of a CR2 file-open operation, used to drive
 * progress UI and navigation to the RAW editor.
 */
sealed interface OpenState {

    /** No file-open operation in progress. */
    data object Idle : OpenState

    /** Dual-ISO blend kernel is running; [progress] is 0.0–1.0. */
    data class Blending(val progress: Float) : OpenState

    /**
     * File is ready to open in the RAW editor.
     * @param targetUri The URI to open (original CR2, twin DNG, or blended result).
     * @param sidecarUri Optional ML/DATA/SHOTS sidecar (.ml6d) URI if present.
     */
    data class Ready(
        val targetUri: Uri,
        val sidecarUri: Uri?,
    ) : OpenState

    /**
     * Blend or open operation failed.
     * @param message Human-readable error description.
     * @param fallbackUri The original CR2 URI for single-ISO fallback opening.
     */
    data class Error(val message: String, val fallbackUri: Uri) : OpenState
}
