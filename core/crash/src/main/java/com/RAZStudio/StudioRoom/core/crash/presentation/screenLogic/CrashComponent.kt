/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2025 RAZStudio (Fakhrurraze)
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

package com.RAZStudio.StudioRoom.core.crash.presentation.screenLogic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.crash.presentation.components.CrashInfo
import com.RAZStudio.StudioRoom.core.domain.TELEGRAM_GROUP_LINK
import com.RAZStudio.StudioRoom.core.domain.coroutines.DispatchersHolder
import com.RAZStudio.StudioRoom.core.domain.image.ShareProvider
import com.RAZStudio.StudioRoom.core.settings.domain.SettingsManager
import com.RAZStudio.StudioRoom.core.settings.domain.model.SettingsState
import com.RAZStudio.StudioRoom.core.ui.utils.BaseComponent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


class CrashComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val crashInfo: CrashInfo,
    @ApplicationContext private val appContext: Context,
    private val settingsManager: SettingsManager,
    private val shareProvider: ShareProvider,
    dispatchersHolder: DispatchersHolder
) : BaseComponent(dispatchersHolder, componentContext) {

    private val _settingsState = mutableStateOf(SettingsState.Default)
    val settingsState: SettingsState by _settingsState

    init {
        _settingsState.value = SettingsState.Default
        componentScope.launch {
            _settingsState.value = settingsManager.getSettingsState()
        }
        settingsManager.settingsState.onEach {
            _settingsState.value = it
        }.launchIn(componentScope)
    }

    fun shareLogs() {
        componentScope.launch {
            shareProvider.shareUri(
                uri = settingsManager.createLogsExport(),
                onComplete = {}
            )
        }
    }

    /**
     * Auto-share the crash log with Telegram, then run [afterShare] (used to
     * chain into restart). The Telegram app (`org.telegram.messenger` and the
     * common variants) is targeted directly via `Intent.setPackage`, so the
     * system share-sheet doesn't appear — Telegram opens with the log file
     * pre-attached and the t.me group link in the message body. The user
     * still has to tap Send inside Telegram (Android won't let an app post
     * silently to another app's chat without a service-level integration).
     *
     * If Telegram isn't installed we open the group URL in a browser so the
     * user at least lands on the chat and can manually attach the log later.
     * Either way, [afterShare] runs once we've handed the intent off.
     */
    fun shareLogsToTelegramThenRestart(afterShare: () -> Unit) {
        componentScope.launch {
            val telegramPkg = resolveTelegramPackage(appContext)
            val logUriStr   = runCatching { settingsManager.createLogsExport() }.getOrNull()
            val logUri      = logUriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }

            val sent = runCatching {
                if (telegramPkg != null && logUri != null) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage(telegramPkg)
                        putExtra(Intent.EXTRA_STREAM, logUri)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "RAZStudio Room crash log — ${crashInfo.exceptionName}\n" +
                                "Group: $TELEGRAM_GROUP_LINK"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(send)
                    true
                } else if (telegramPkg != null) {
                    // No log file (export failed) — at least open Telegram on the group.
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_GROUP_LINK)).apply {
                        setPackage(telegramPkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(view)
                    true
                } else {
                    // Telegram not installed — open the group URL in the browser.
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_GROUP_LINK)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(view)
                    true
                }
            }.getOrDefault(false)

            // Always proceed with the restart even if the share failed —
            // user pressed Restart and shouldn't be stranded on the crash screen.
            afterShare()
            if (!sent) android.util.Log.w("CrashComponent", "Telegram share intent failed")
        }
    }

    private fun resolveTelegramPackage(ctx: Context): String? {
        // Cover the standard Telegram, Telegram X, and the Google-Play "web"
        // build (some devices ship variants). First match wins.
        val candidates = listOf(
            "org.telegram.messenger",         // official
            "org.telegram.messenger.web",     // play-store variant on some OEMs
            "org.thunderdog.challegram",      // Telegram X
        )
        val pm = ctx.packageManager
        return candidates.firstOrNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
    }

    @AssistedFactory
    fun interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            crashInfo: CrashInfo
        ): CrashComponent
    }

}