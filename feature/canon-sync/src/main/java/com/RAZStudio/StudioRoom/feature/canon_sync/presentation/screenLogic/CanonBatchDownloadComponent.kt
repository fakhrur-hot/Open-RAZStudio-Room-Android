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

package com.RAZStudio.StudioRoom.feature.canon_sync.presentation.screenLogic

import android.net.Uri
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Batch Download screen backing component. Drives the
 * "pull every photo, then watch every 10 s" pipeline on the
 * repository.
 */
class CanonBatchDownloadComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    private val repository: CanonSyncRepository,
    private val settingsManager: SettingsManager,
    val rawBatchProcessor: com.RAZStudio.StudioRoom.feature.photo_editor.raw
        .RawBatchProcessor,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    internal val batchState: StateFlow<CanonSyncRepository.CanonBatchPhase> = repository.batchState

    /**
     * App-default Default Output folder Uri. The screen offers this
     * as a one-tap fallback when the user doesn't want to pick a
     * dedicated folder via SAF.
     */
    val settingsDefaultFolder: StateFlow<Uri?> = settingsManager.settingsState
        .map { state ->
            state.saveFolderUri
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme == "content" }
        }
        .stateIn(componentScope, SharingStarted.WhileSubscribed(5_000L), null)

    internal fun start(destFolder: Uri, format: CanonSyncRepository.BatchFormat) =
        repository.startBatchDownload(destFolder, format)

    /**
     * Start a download + process pipeline. The processor is configured
     * from [perFileContext] which the screen builds via
     * [com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor.prepareContext]
     * with the user's settings-panel choices.
     */
    internal fun startDownloadAndProcess(
        destFolder: Uri,
        format: CanonSyncRepository.BatchFormat,
        perFileContext: com.RAZStudio.StudioRoom.feature.photo_editor.raw
            .RawBatchProcessor.PerFileContext,
        processExisting: Boolean = false,
    ) = repository.startBatchDownloadAndProcess(
        destFolder, format, perFileContext, processExisting,
    )

    internal val processingState = repository.processingState

    fun stop() = repository.stopBatchDownload()

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
        ): CanonBatchDownloadComponent
    }
}
