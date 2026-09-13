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
 * Represents a detected SD card volume, reachable via one of two independent
 * transports:
 *  - [Saf]: the OS auto-mounted the card reader as a native StorageVolume
 *    (works when the device's USB stack supports OTG mass-storage automount).
 *  - [Usb]: the OS never mounted it — StudioRoom claimed the raw USB device
 *    directly via libaums (bulk-only transport + FAT), the same approach
 *    third-party file managers use when automount isn't available.
 */
sealed interface SdCardVolume {
    val root: FileHandle

    data class Saf(
        val path: String,
        val treeUri: Uri,
        override val root: FileHandle.Saf,
    ) : SdCardVolume

    data class Usb(
        val deviceName: String,
        override val root: FileHandle.Usb,
    ) : SdCardVolume
}
