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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.presentation

import android.net.Uri

/**
 * UI state for the SD Card Browser screen.
 */
sealed interface SdCardBrowserUiState {

    /** No SD card detected. Show empty state with instruction text. */
    data object Empty : SdCardBrowserUiState

    /** Detecting volume and loading initial folder. */
    data object Loading : SdCardBrowserUiState

    /** A USB Mass Storage device is attached but StudioRoom needs permission to open it. */
    data object UsbPermissionRequested : SdCardBrowserUiState

    /** The user denied USB permission, or the device has no readable partition. */
    data class UsbError(val message: String) : SdCardBrowserUiState

    /** Actively browsing. */
    data class Browsing(
        val currentPath: String,
        val entries: List<BrowserGridItem>,
        val mlDetected: Boolean,
        val canGoBack: Boolean,
    ) : SdCardBrowserUiState

    /** Blend in progress for a dual-ISO file. */
    data class Blending(
        val filename: String,
        val progress: Float,
    ) : SdCardBrowserUiState

    /** Blend failed — offer single-ISO fallback. */
    data class BlendError(
        val filename: String,
        val message: String,
        val fallbackUri: Uri,
    ) : SdCardBrowserUiState
}
