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

import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.DualIsoDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Manages folder-tree navigation through the SD card's DCIM hierarchy,
 * maintaining a back stack for parent folder traversal. Works uniformly over
 * either a SAF-mounted volume or a raw USB Mass Storage device via [FileHandle].
 */
class FolderNavigator @Inject constructor(
    private val dispatchers: DispatchersHolder,
) {
    private val scope = CoroutineScope(dispatchers.defaultDispatcher + SupervisorJob())

    private val _backStack = MutableStateFlow<List<FileHandle>>(emptyList())

    /** Current folder path as a back-stack. Root is index 0. */
    val backStack: StateFlow<List<FileHandle>> = _backStack.asStateFlow()

    private val _currentEntries = MutableStateFlow<List<FolderEntry>>(emptyList())

    /** Current folder's contents — subfolders first, then CR2 files. */
    val currentEntries: StateFlow<List<FolderEntry>> = _currentEntries.asStateFlow()

    /**
     * List the contents of [folder], filtering out:
     *  - ML/data/ directory at any level (case-insensitive)
     *  - .ml6d files at any level
     * Returns subfolders first (sorted by name), then CR2 files (sorted by name).
     */
    suspend fun loadFolder(folder: FileHandle): List<FolderEntry> =
        withContext(dispatchers.ioDispatcher) {
            val children = folder.listChildren()
            val directories = mutableListOf<FolderEntry.Directory>()
            val files = mutableListOf<FolderEntry.Cr2File>()

            for (child in children) {
                val name = child.name

                // Filter: hide ML/data/ directory (case-insensitive — the deployed
                // ML_6D firmware writes a lowercase `data` directory)
                if (child.isDirectory && name.equals("data", ignoreCase = true)) {
                    if (folder.name.equals("ML", ignoreCase = true)) continue
                }

                // Filter: hide .ml6d files everywhere
                if (name.endsWith(".ml6d", ignoreCase = true)) continue

                when {
                    child.isDirectory -> directories.add(
                        FolderEntry.Directory(name, child)
                    )
                    isSupportedPhotoFile(name) -> files.add(
                        FolderEntry.Cr2File(
                            name = name,
                            handle = child,
                            isDualIso = DualIsoDetector.detectFromFilename(name),
                        )
                    )
                }
            }

            directories.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            directories + files
        }

    /** Push [folder] onto the back stack and load its contents. */
    fun navigateInto(folder: FileHandle) {
        _backStack.value = _backStack.value + folder
        scope.launch {
            _currentEntries.value = loadFolder(folder)
        }
    }

    /** Reset the back stack to a new root (called on volume change). */
    fun resetTo(root: FileHandle) {
        _backStack.value = listOf(root)
        scope.launch {
            _currentEntries.value = loadFolder(root)
        }
    }

    /** Pop the back stack and load the parent folder. No-op at root. Returns false if already at root. */
    fun navigateBack(): Boolean {
        val current = _backStack.value
        if (current.size <= 1) return false

        val newStack = current.dropLast(1)
        _backStack.value = newStack
        val parentFolder = newStack.last()
        scope.launch {
            _currentEntries.value = loadFolder(parentFolder)
        }
        return true
    }

    /** Whether DCIM exists at volume root. */
    suspend fun hasDcim(volumeRoot: FileHandle): Boolean =
        withContext(dispatchers.ioDispatcher) {
            volumeRoot.listChildren().any { it.isDirectory && it.name.equals("DCIM", ignoreCase = true) }
        }

    /** Find the DCIM child of [volumeRoot], if present. */
    suspend fun findDcim(volumeRoot: FileHandle): FileHandle? =
        withContext(dispatchers.ioDispatcher) {
            volumeRoot.listChildren().firstOrNull { it.isDirectory && it.name.equals("DCIM", ignoreCase = true) }
        }

    companion object {
        /**
         * All photo/RAW extensions supported by the SD Card Browser.
         * Covers JPEG, Canon, Sony, Nikon, Fujifilm, Pentax, Leica,
         * Panasonic/Lumix, Olympus/OM System, DNG, and others.
         */
        private val SUPPORTED_EXTENSIONS = setOf(
            // JPEG
            "jpg", "jpeg",
            // Canon
            "cr2", "cr3", "crw",
            // Sony
            "arw", "srf", "sr2",
            // Nikon
            "nef", "nrw",
            // Fujifilm
            "raf",
            // Pentax
            "pef", "dng",
            // Leica
            "rwl", "dng",
            // Panasonic / Lumix
            "rw2",
            // Olympus / OM System
            "orf",
            // Adobe DNG (universal)
            "dng",
            // Samsung
            "srw",
            // Sigma / Foveon
            "x3f",
            // Hasselblad
            "3fr", "fff",
            // Phase One
            "iiq",
            // Kodak
            "dcr", "kdc",
        )

        /** Returns true if [filename] has a supported photo/RAW extension. */
        fun isSupportedPhotoFile(filename: String): Boolean {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return ext in SUPPORTED_EXTENSIONS
        }
    }
}
