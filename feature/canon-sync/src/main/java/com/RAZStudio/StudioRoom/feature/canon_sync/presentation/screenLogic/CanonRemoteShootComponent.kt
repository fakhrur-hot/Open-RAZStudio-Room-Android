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

import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CanonSyncRepository
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ConnectionState
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.EosPropertyDescriptor
import com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonWifiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.StateFlow

/**
 * Live Remote Shooting screen backing component.
 *
 * Owns the live-view start/stop lifecycle: the screen calls
 * [startLiveView] in its `LaunchedEffect(Unit)` and the screen's
 * `DisposableEffect` returns through [stopLiveView] when the user
 * navigates away. The screen also pops back automatically on session
 * loss — see the `LaunchedEffect(connectionState)` in
 * `CanonRemoteShootContent`.
 *
 * Public surface:
 *  - [connectionState] / [liveViewState] / [captureInProgress] — read.
 *  - [startLiveView] / [stopLiveView] — viewfinder stream control.
 *  - [triggerShutter] / [doAf] / [cancelAf] — capture + AF actions.
 *  - [driveLens] — manual-focus 6-step panel.
 *  - [setCaptureDestination] — save-to-card / save-to-PC / both.
 */
class CanonRemoteShootComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val onGoBack: () -> Unit,
    private val repository: CanonSyncRepository,
    dispatchersHolder: DispatchersHolder,
) : BaseComponent(dispatchersHolder, componentContext) {

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    internal val liveViewState: StateFlow<CanonSyncRepository.LiveViewState> = repository.liveViewState
    val captureInProgress: StateFlow<Boolean> = repository.captureInProgress

    /**
     * Latest auto-download event from [CaptureCoordinator] — observed
     * by the screen for the "saved 'IMG_4096.CR2'" / "downloading…"
     * badge. Null until the first shot is processed in this session.
     */
    internal val latestCaptureEvent =
        repository.latestCaptureEvent

    fun startLiveView() = repository.startLiveView()
    fun stopLiveView() = repository.stopLiveView()
    fun triggerShutter() = repository.triggerShutter()

    /**
     * Make sure the post-shutter auto-download pipeline is wired up.
     * The screen calls this on mount so every shot taken via the
     * remote shutter is queued for transfer into the app's working
     * directory.
     */
    fun ensureCaptureCoordinator() = repository.ensureCaptureCoordinator()
    fun doAf() = repository.doAf()
    fun cancelAf() = repository.cancelAf()

    /** True while the AF-On button is actively held — drives green glow. */
    val afEngaged: StateFlow<Boolean> = repository.afEngaged
    internal fun driveLens(step: CanonWifiClient.LensFocusStep) = repository.driveLens(step)
    fun setCaptureDestination(value: Int) = repository.setCaptureDestination(value)

    /**
     * Reposition the FlexiZone-AF rectangle to the user's tap position.
     * [normalisedX] / [normalisedY] are 0..1 fractions of the LV
     * canvas in screen-space. The repository scales these into the
     * camera's native pixel grid and triggers AF.
     */
    fun setLiveAfPoint(normalisedX: Float, normalisedY: Float) =
        repository.setLiveAfPoint(normalisedX, normalisedY)

    /**
     * Observe the live descriptor for [dpc]. Flow emits null until the
     * first descriptor fetch completes and again whenever the camera
     * pushes a property-changed event.
     *
     * Implementation note: the controller is per-session, so when the
     * session re-opens the controller reference changes too. We bind
     * via the [connectionState] flow so the observer switches to the
     * new controller automatically on reconnect.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeProperty(dpc: Int): Flow<EosPropertyDescriptor?> =
        connectionState.flatMapLatest {
            val controller = repository.propertyController
            if (controller == null) flowOf(null)
            else controller.observe(dpc, componentScope)
        }

    /**
     * Write [value] to the camera-side property [dpc] via the live
     * controller. No-op when the session is down.
     */
    internal suspend fun setProperty(dpc: Int, value: Long): Boolean {
        val controller = repository.propertyController ?: return false
        return controller.setValue(dpc, value)
    }

    /**
     * Live OLC info push from the camera (AE-lock / AF-lock / flash-
     * ready bits). Re-binds across reconnects via [connectionState].
     * Emits null until the camera sends its first OLC record.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val olcInfo: Flow<com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.PtpEvent.EosOlcInfo?> =
        connectionState.flatMapLatest {
            val client = repository.activeClient
            if (client == null) flowOf(null)
            else client.olcInfo
        }

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            onGoBack: () -> Unit,
        ): CanonRemoteShootComponent
    }
}
