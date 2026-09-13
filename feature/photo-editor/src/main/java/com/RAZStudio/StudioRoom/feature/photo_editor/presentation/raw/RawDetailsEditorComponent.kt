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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arkivanov.decompose.ComponentContext
import com.RAZStudio.StudioRoom.core.ui.utils.navigation.Screen
import com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.components.TAB_DETAILS
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * Component for the final-quality Details editor workspace.
 *
 * Stage A from the source RAW is full-res 16-bit linear FP16 BigTIFF
 * (see [com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Cache]),
 * so reusing the same Stage A path through a shadow [RawEditorComponent]
 * already gives us the full-sensor source the Details editor wants.
 *
 * The shadow editor owns the GL preview pipeline (AHB binding, Stage B
 * downsample, uniform pushes, segmentation, LUT chain). To avoid showing
 * the workspace-selector dialog twice, we bypass it here: the user
 * already picked a workspace when they opened the photo in the main
 * editor, so we re-use that config from prefs and kick Stage A
 * immediately on construction.
 *
 * Apply commits a [RawAction] with `tabIndex = TAB_DETAILS` to the
 * shadow editor's action stack. Because both editors persist via
 * [com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawActionsStorage]
 * keyed by `uri.toString()`, the commit hits the same on-disk file the
 * main editor reads. The main editor reloads its action stack via its
 * own `doOnResume` hook, so when the user navigates back the
 * RawEditor → RawExport page reflects the new Details adjustments.
 */
class RawDetailsEditorComponent @AssistedInject internal constructor(
    @Assisted componentContext: ComponentContext,
    @Assisted val initialUri: Uri?,
    @Assisted val onGoBack: () -> Unit,
    @Assisted val onNavigate: (Screen) -> Unit,
    private val rawEditorComponentFactory: RawEditorComponent.Factory,
    @ApplicationContext private val appContext: Context,
) : ComponentContext by componentContext {

    val shadowEditor: RawEditorComponent = rawEditorComponentFactory(
        componentContext = componentContext,
        initialUri = initialUri,
        onGoBack = onGoBack,
        onNavigate = onNavigate,
        // The Details shadow editor is never opened with Project context — it
        // mirrors whatever the MAIN editor already has open, and bypasses the
        // workspace dialog entirely below (see confirmWorkspace call).
        projectContext = null,
    )

    init {
        // Strip any previously-committed Details-tab cards so the Details
        // sliders here start from a clean slate. Without this, opening the
        // Details editor on a photo that already has a Details Apply card
        // would stack the new edits on top of the old ones.
        val stripped = shadowEditor.actions.filter { it.tabIndex != TAB_DETAILS }
        if (stripped.size != shadowEditor.actions.size) {
            shadowEditor.replaceActions(stripped)
        }
        // Bypass the workspace-selector dialog: the user already picked a
        // config in the main editor, so use the saved prefs. Without this
        // the shadow editor sits in DialogShown forever and Stage A
        // never runs — the preview canvas stays blank.
        initialUri?.let { uri ->
            shadowEditor.confirmWorkspace(uri, WorkspaceConfig.fromPrefs(appContext))
        }
    }

    /**
     * In-flight Details macro. The Details tab edits this; on every change
     * we push it through [RawEditorComponent.updateMacro] so the shadow
     * editor recomposes [shadowEditor.shaderParamsFlow] and the GL preview
     * updates live.
     */
    var detailsMacro: UserMacro by mutableStateOf(UserMacro())
        private set

    fun updateDetailsMacro(next: UserMacro) {
        detailsMacro = next
        shadowEditor.updateMacro(next)
    }

    /**
     * Commit the current Details macro as a new [RawAction] (tabIndex =
     * TAB_DETAILS) on the shadow editor's action stack. The stack is
     * persisted to disk synchronously; the main editor picks it up via
     * its own `doOnResume` reload when the user navigates back.
     */
    fun triggerApplyAndSave() {
        shadowEditor.addAction(
            RawAction(
                label = "Details",
                tabIndex = TAB_DETAILS,
                macro = detailsMacro,
            )
        )
        // Force a synchronous write so the main editor's doOnResume reload
        // sees the committed card. addAction launches persistActions on IO,
        // which races with the navigateBack about to fire.
        val uri = initialUri ?: return
        com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawActionsStorage.save(
            appContext, uri.toString(), shadowEditor.actions.toList()
        )
    }

    @AssistedFactory
    interface Factory {
        operator fun invoke(
            componentContext: ComponentContext,
            initialUri: Uri?,
            onGoBack: () -> Unit,
            onNavigate: (Screen) -> Unit,
        ): RawDetailsEditorComponent
    }
}
