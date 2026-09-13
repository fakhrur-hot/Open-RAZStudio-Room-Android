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

/**
 * Represents an entry in a folder listing on the SD card.
 * Directories are displayed as tappable folder items; Cr2Files are displayed
 * in the thumbnail grid with optional dual-ISO badge.
 */
sealed interface FolderEntry {
    val name: String
    val handle: FileHandle

    data class Directory(
        override val name: String,
        override val handle: FileHandle,
    ) : FolderEntry

    data class Cr2File(
        override val name: String,
        override val handle: FileHandle,
        val isDualIso: Boolean,
    ) : FolderEntry
}
