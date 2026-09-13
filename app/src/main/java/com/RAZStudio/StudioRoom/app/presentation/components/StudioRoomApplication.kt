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

package com.RAZStudio.StudioRoom.app.presentation.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.RAZStudio.StudioRoom.app.presentation.components.functions.attachLogWriter
import com.RAZStudio.StudioRoom.core.settings.di.SettingsStateEntryPoint
import com.RAZStudio.StudioRoom.core.utils.AppLogger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.RAZStudio.StudioRoom.app.presentation.components.functions.initCollages
import com.RAZStudio.StudioRoom.core.ui.utils.helper.DeviceIdentityOverride
import com.RAZStudio.StudioRoom.app.presentation.components.functions.initColorNames
import com.RAZStudio.StudioRoom.app.presentation.components.functions.initNeuralTool
import com.RAZStudio.StudioRoom.app.presentation.components.functions.initOpenCV
import com.RAZStudio.StudioRoom.app.presentation.components.functions.initPdfBox
import com.RAZStudio.StudioRoom.app.presentation.components.functions.initQrScanner
import com.RAZStudio.StudioRoom.app.presentation.components.functions.injectBaseComponent
import com.RAZStudio.StudioRoom.app.presentation.components.functions.registerSecurityProviders
import com.RAZStudio.StudioRoom.app.presentation.components.functions.setupFlags
import com.RAZStudio.StudioRoom.app.presentation.components.utils.isMain
import com.RAZStudio.StudioRoom.core.crash.presentation.components.applyGlobalExceptionHandler
import com.RAZStudio.StudioRoom.core.domain.coroutines.AppScope
import com.RAZStudio.StudioRoom.core.domain.remote.AnalyticsManager
import com.RAZStudio.StudioRoom.core.domain.saving.KeepAliveService
import com.RAZStudio.StudioRoom.core.resources.emoji.Emoji.initEmoji
import com.RAZStudio.StudioRoom.core.ui.utils.ComposeApplication
import com.RAZStudio.StudioRoom.core.utils.initAppContext
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@HiltAndroidApp
class StudioRoomApplication : ComposeApplication() {

    @Inject
    lateinit var keepAliveService: KeepAliveService

    @Inject
    lateinit var appScope: AppScope

    @Inject
    lateinit var httpClient: HttpClient

    @Inject
    lateinit var analyticsManager: AnalyticsManager

    private var isSetupCompleted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        runSetup()
    }

    override fun runSetup() {
        if (isSetupCompleted) return

        if (isMain()) {
            // ── Critical path: must finish before the first frame ────────────────
            // These either install handlers that catch early crashes, wire the DI
            // graph used by the first Activity, or set context/flags read at launch.
            // initOpenCV is just a context-pointer assignment (~µs); the heavy
            // OpenCVLoader work happens lazily inside the OpenCV class init block,
            // so it's cheap to keep on the critical path.
            setupFlags()
            initAppContext()
            // App-level device identity override — must run before attachLogWriter
            // so the first DeviceInfo.get() call already returns the custom values.
            DeviceIdentityOverride.init(this)
            registerDeviceIdentityReceiver()
            attachLogWriter()
            applyGlobalExceptionHandler()
            registerSecurityProviders()
            injectBaseComponent()
            observeDebugModeToggle()
            initOpenCV()
            initNeuralTool()

            // ── Deferred: heavy library init that the launcher screen doesn't need
            // before the user navigates to the feature that uses it. Pushing these
            // off the main thread eliminates ~1 s of cold-start jank seen in logcat
            // (Skipped 70 frames + Davey 1494 ms during AppActivity create).
            appScope.launch(Dispatchers.Default) {
                initColorNames()
                initQrScanner()
                initEmoji()
                initPdfBox()
                initCollages()
            }

            isSetupCompleted = true
        }
    }

    /**
     * Observes [SettingsManager.settingsState].isDebugMode and forwards every
     * distinct change to [AppLogger.setEnabled].
     *
     * - Debug build: toggle is surfaced in Settings UI; turning it off silences
     *   all logcat output from the app immediately without a restart.
     * - Release build: [AppLogger.setEnabled] ignores the value and forces
     *   `isEnabled = false`, so this observer is effectively a no-op at runtime
     *   (and ProGuard strips the log bodies at compile time anyway).
     */
    private fun observeDebugModeToggle() {
        val settingsManager = EntryPointAccessors
            .fromApplication(this, SettingsStateEntryPoint::class.java)
            .settingsManager
        appScope.launch(Dispatchers.Default) {
            settingsManager.settingsState
                .map { it.isDebugMode }
                .distinctUntilChanged()
                .collect { isDebug ->
                    AppLogger.setEnabled(isDebug)
                    Log.i("AppLogger",
                        "Debug logging ${if (AppLogger.isEnabled) "ENABLED" else "DISABLED"}")
                }
        }
    }

    /**
     * Registers a broadcast receiver that lets ADB set or clear the app-level
     * device identity override without root.
     *
     * Set:
     *   adb shell am broadcast -a com.RAZStudio.StudioRoom.SET_DEVICE_IDENTITY \
     *       --es model "Pixel 9 Pro" --es brand "google" --es device "caiman"
     *
     * Clear (revert to real Build.* values):
     *   adb shell am broadcast -a com.RAZStudio.StudioRoom.SET_DEVICE_IDENTITY
     */
    private fun registerDeviceIdentityReceiver() {
        val action = "$packageName.SET_DEVICE_IDENTITY"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val model  = intent.getStringExtra("model")
                val brand  = intent.getStringExtra("brand")
                val device = intent.getStringExtra("device")
                if (model == null && brand == null && device == null) {
                    DeviceIdentityOverride.clear()
                    Log.i("DeviceIdentity", "Override cleared — using real Build.* values")
                } else {
                    DeviceIdentityOverride.set(model, brand, device)
                    Log.i("DeviceIdentity",
                        "Override set: model=${DeviceIdentityOverride.model} " +
                        "brand=${DeviceIdentityOverride.brand} " +
                        "device=${DeviceIdentityOverride.device}")
                }
            }
        }
        registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
    }

}