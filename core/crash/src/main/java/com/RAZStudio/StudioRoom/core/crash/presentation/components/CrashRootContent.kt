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

package com.RAZStudio.StudioRoom.core.crash.presentation.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.crash.presentation.screenLogic.CrashComponent
import com.RAZStudio.StudioRoom.core.settings.presentation.model.toUiState
import com.RAZStudio.StudioRoom.core.ui.utils.helper.AppActivityClass
import com.RAZStudio.StudioRoom.core.ui.utils.helper.Clipboard
import com.RAZStudio.StudioRoom.core.ui.utils.provider.StudioRoomCompositionLocals
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.enhancedVerticalScroll

@Composable
internal fun CrashRootContent(component: CrashComponent) {
    val context = LocalContext.current
    val crashInfo = component.crashInfo


    StudioRoomCompositionLocals(
        settingsState = component.settingsState.toUiState()
    ) {
        val copyCrashInfo: () -> Unit = {
            Clipboard.copy(crashInfo.textToSend)
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .enhancedVerticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 80.dp)
                .navigationBarsPadding()
                .displayCutoutPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CrashAttentionCard()
            Spacer(modifier = Modifier.height(24.dp))
            CrashActionButtons(
                onCopyCrashInfo = copyCrashInfo,
                onShareLogs = component::shareLogs,
                githubLink = crashInfo.githubLink
            )
            Spacer(modifier = Modifier.height(24.dp))
            CrashInfoCard(crashInfo = crashInfo)
        }

        CrashBottomButtons(
            modifier = Modifier.align(Alignment.BottomCenter),
            onCopy = copyCrashInfo,
            onRestartApp = {
                // Auto-share the crash log to Telegram (or fall back to the group
                // URL in a browser) before restarting. Telegram still requires a
                // manual Send tap inside its compose screen — Android does not
                // allow silent posting into another app's chat without a system
                // integration we don't have. The restart proceeds in either case
                // so the user is never stuck on the crash screen.
                component.shareLogsToTelegramThenRestart {
                    context.startActivity(
                        Intent(context, AppActivityClass)
                    )
                }
            }
        )
    }
}