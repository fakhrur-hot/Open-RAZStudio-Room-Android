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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.presentation.screenLogic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.sd_card_browser.data.FileOpener
import com.RAZStudio.StudioRoom.feature.sd_card_browser.data.MlBadgeChecker
import com.RAZStudio.StudioRoom.feature.sd_card_browser.data.SdCardVolumeDetector
import com.RAZStudio.StudioRoom.feature.sd_card_browser.data.ThumbnailExtractor
import com.RAZStudio.StudioRoom.feature.sd_card_browser.data.UsbMsdState
import com.RAZStudio.StudioRoom.feature.sd_card_browser.data.UsbMsdVolumeDetector
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FolderEntry
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FolderNavigator
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.OpenState
import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.SdCardVolume
import com.RAZStudio.StudioRoom.feature.sd_card_browser.presentation.BrowserGridItem
import com.RAZStudio.StudioRoom.feature.sd_card_browser.presentation.SdCardBrowserUiState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SdCardBrowserComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    @ApplicationContext private val context: Context,
    private val volumeDetector: SdCardVolumeDetector,
    private val usbMsdVolumeDetector: UsbMsdVolumeDetector,
    private val folderNavigator: FolderNavigator,
    private val thumbnailExtractor: ThumbnailExtractor,
    private val mlBadgeChecker: MlBadgeChecker,
    private val fileOpener: FileOpener,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    private val _uiState = MutableStateFlow<SdCardBrowserUiState>(SdCardBrowserUiState.Loading)
    val uiState: StateFlow<SdCardBrowserUiState> = _uiState.asStateFlow()

    private var currentVolume: SdCardVolume? = null
    private val thumbnails = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())

    init {
        // Prefer a natively-mounted SAF volume; fall back to claiming the raw
        // USB device directly (libaums) when the OS never mounts it — see the
        // "CX File Explorer took over the OTG device" investigation.
        componentScope.launch {
            volumeDetector.observeVolume().collect { volume ->
                if (volume != null) {
                    onVolumeFound(volume)
                } else if (currentVolume is SdCardVolume.Saf || currentVolume == null) {
                    onVolumeLost()
                }
            }
        }

        componentScope.launch {
            usbMsdVolumeDetector.observe().collect { state ->
                // SAF takes priority — if a mounted volume is already active, ignore USB events.
                if (currentVolume is SdCardVolume.Saf) return@collect
                when (state) {
                    is UsbMsdState.NoDevice -> onVolumeLost()
                    is UsbMsdState.PermissionRequested -> _uiState.value = SdCardBrowserUiState.UsbPermissionRequested
                    is UsbMsdState.PermissionDenied ->
                        _uiState.value = SdCardBrowserUiState.UsbError("Permission denied for ${state.deviceName}")
                    is UsbMsdState.Error -> _uiState.value = SdCardBrowserUiState.UsbError(state.message)
                    is UsbMsdState.Ready ->
                        onVolumeFound(SdCardVolume.Usb(state.deviceName, state.root))
                }
            }
        }

        componentScope.launch {
            folderNavigator.currentEntries.collect { entries ->
                val volume = currentVolume ?: return@collect
                updateBrowsingState(entries, volume)
            }
        }
    }

    private fun onVolumeLost() {
        currentVolume = null
        mlBadgeChecker.invalidate()
        _uiState.value = SdCardBrowserUiState.Empty
    }

    private fun onVolumeFound(volume: SdCardVolume) {
        if (volume == currentVolume) return
        currentVolume = volume
        mlBadgeChecker.invalidate()
        componentScope.launch { loadInitialFolder(volume) }
    }

    private suspend fun loadInitialFolder(volume: SdCardVolume) {
        _uiState.value = SdCardBrowserUiState.Loading
        thumbnails.value = emptyMap()

        val root = volume.root
        val dcim = folderNavigator.findDcim(root)
        folderNavigator.resetTo(dcim ?: root)
    }

    private suspend fun updateBrowsingState(entries: List<FolderEntry>, volume: SdCardVolume) {
        val mlDetected = mlBadgeChecker.isMlPresent(volume.root)
        val gridItems = entries.map { entry ->
            when (entry) {
                is FolderEntry.Directory -> BrowserGridItem.Folder(
                    name = entry.name,
                    handle = entry.handle,
                )

                is FolderEntry.Cr2File -> BrowserGridItem.Photo(
                    name = entry.name,
                    handle = entry.handle,
                    thumbnail = thumbnails.value[entry.name],
                    isDualIso = entry.isDualIso,
                    showMlBadge = mlDetected,
                )
            }
        }

        val currentPath = folderNavigator.backStack.value
            .joinToString("/") { it.name }

        _uiState.value = SdCardBrowserUiState.Browsing(
            currentPath = currentPath,
            entries = gridItems,
            mlDetected = mlDetected,
            canGoBack = folderNavigator.backStack.value.size > 1,
        )

        // Extract thumbnails progressively
        val cr2Files = entries.filterIsInstance<FolderEntry.Cr2File>()
            .map { it.handle }
        componentScope.launch {
            thumbnailExtractor.extractThumbnailsFlow(cr2Files).collect { (name, bitmap) ->
                thumbnails.value = thumbnails.value + (name to bitmap)
                val currentState = _uiState.value
                if (currentState is SdCardBrowserUiState.Browsing) {
                    val updatedEntries = currentState.entries.map { item ->
                        if (item is BrowserGridItem.Photo && item.name == name) {
                            item.copy(thumbnail = bitmap)
                        } else {
                            item
                        }
                    }
                    _uiState.value = currentState.copy(entries = updatedEntries)
                }
            }
        }
    }

    fun onFolderTap(entry: FolderEntry.Directory) {
        folderNavigator.navigateInto(entry.handle)
    }

    fun onFileTap(entry: FolderEntry.Cr2File) {
        val volume = currentVolume ?: return
        val parentTreeUri = (volume as? SdCardVolume.Saf)?.treeUri
        componentScope.launch {
            fileOpener.openCr2(entry, parentTreeUri, volume.root).collect { state ->
                when (state) {
                    is OpenState.Idle -> {}
                    is OpenState.Blending -> {
                        _uiState.value = SdCardBrowserUiState.Blending(
                            filename = entry.name,
                            progress = state.progress,
                        )
                    }

                    is OpenState.Ready -> {
                        onNavigate(Screen.RawEditor(uri = state.targetUri))
                    }

                    is OpenState.Error -> {
                        _uiState.value = SdCardBrowserUiState.BlendError(
                            filename = entry.name,
                            message = state.message,
                            fallbackUri = state.fallbackUri,
                        )
                    }
                }
            }
        }
    }

    fun onBack(): Boolean {
        return folderNavigator.navigateBack()
    }

    fun onOpenSingleIsoFallback(uri: Uri) {
        onNavigate(Screen.RawEditor(uri = uri))
    }

    @AssistedFactory
    interface Factory {
        fun create(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): SdCardBrowserComponent
    }

    companion object {
        private const val STATE_TIMEOUT_MS = 5_000L
    }
}
