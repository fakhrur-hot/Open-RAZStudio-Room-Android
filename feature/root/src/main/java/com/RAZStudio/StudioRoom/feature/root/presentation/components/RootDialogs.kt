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

package com.RAZStudio.StudioRoom.feature.root.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalEditPresetsController
import com.RAZStudio.StudioRoom.core.ui.utils.helper.Clipboard
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ReviewHandler
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.core.ui.widget.sheets.ProcessImagesPreferenceSheet
import com.RAZStudio.StudioRoom.core.ui.widget.sheets.UpdateSheet
import com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs.AppExitDialog
import com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs.EditPresetsSheet
import com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs.FirstLaunchSetupDialog
import com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs.GithubReviewDialog
import com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs.PermissionDialog
import com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs.TelegramGroupDialog
import com.RAZStudio.StudioRoom.feature.root.presentation.components.utils.HandleLookForUpdates
import com.RAZStudio.StudioRoom.feature.root.presentation.components.utils.SuccessRestoreBackupToastHandler
import com.RAZStudio.StudioRoom.feature.root.presentation.screenLogic.RootComponent
import com.RAZStudio.StudioRoom.feature.settings.presentation.components.additional.DonateDialog

@Composable
internal fun RootDialogs(component: RootComponent) {
    val editPresetsController = LocalEditPresetsController.current

    AppExitDialog(component)

    EditPresetsSheet(
        visible = editPresetsController.isVisible,
        onDismiss = editPresetsController::close,
        onUpdatePresets = component::setPresets
    )

    // Auto-route image SEND intents straight to RAWEditor (no tool-picker
    // sheet). The ProcessImagesPreferenceSheet was originally the "which tool
    // do you want to open this image in?" picker — useful when the app had
    // 35+ image tools. After the RAW-only fork-down the picker would always
    // show a single option, so we skip the sheet entirely and navigate.
    //
    // Conditions: dialog wants to show, a single URI was shared. The launched
    // effect is keyed on `showSelectDialog` so it fires once per intent rather
    // than on every recomposition.
    val uris = component.uris
    val sharedUri = uris?.firstOrNull()
    LaunchedEffect(component.showSelectDialog, sharedUri) {
        if (component.showSelectDialog && sharedUri != null) {
            if (!com.RAZStudio.StudioRoom.feature.main.presentation.components
                    .TrialExpiry.isActiveBlocking()
            ) {
                component.hideSelectDialog()
                return@LaunchedEffect
            }
            component.hideSelectDialog()
            component.navigateTo(Screen.RawEditor(sharedUri))
            Clipboard.clear()
        }
    }

    // Sheet is kept in the tree as a fallback for any edge case where the
    // LaunchedEffect above hasn't fired (e.g. extraDataType-only flow with no
    // URIs — the bypass won't kick in there). With singleImageScreens trimmed
    // to one entry it just shows a one-item list, which we'll hand off the
    // same way via the existing onNavigate callback.
    ProcessImagesPreferenceSheet(
        uris = component.uris ?: emptyList(),
        extraDataType = component.extraDataType,
        visible = component.showSelectDialog,
        onDismiss = component::hideSelectDialog,
        onNavigate = { screen ->
            component.navigateTo(screen)
            Clipboard.clear()
        }
    )

    PermissionDialog()

    SuccessRestoreBackupToastHandler(component)
}