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

package com.RAZStudio.StudioRoom.feature.root.presentation.components.dialogs

import android.Manifest
import android.os.Build
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.Storage
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSettingsState
import com.RAZStudio.StudioRoom.core.settings.presentation.provider.LocalSimpleSettingsInteractor
import com.RAZStudio.StudioRoom.core.ui.utils.content_pickers.rememberFolderPicker
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.needToShowStoragePermissionRequest
import com.RAZStudio.StudioRoom.core.ui.utils.helper.ContextUtils.requestStoragePermission
import com.RAZStudio.StudioRoom.core.ui.utils.permission.PermissionUtils.hasPermissionAllowed
import com.RAZStudio.StudioRoom.core.ui.utils.provider.LocalComponentActivity
import com.RAZStudio.StudioRoom.core.ui.utils.provider.rememberCurrentLifecycleEvent
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedAlertDialog
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PermissionDialog() {
    val context = LocalComponentActivity.current
    val settingsState = LocalSettingsState.current
    val settingsInteractor = LocalSimpleSettingsInteractor.current
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    var showOutputFolderDialog by rememberSaveable { mutableStateOf(false) }

    val currentLifecycleEvent = rememberCurrentLifecycleEvent()
    LaunchedEffect(
        showDialog,
        context,
        settingsState,
        currentLifecycleEvent
    ) {
        showDialog = context.needToShowStoragePermissionRequest()
        while (showDialog) {
            showDialog = context.needToShowStoragePermissionRequest()
            delay(100)
        }

        if (!showDialog && settingsState.saveFolderUri == null) {
            showOutputFolderDialog = true
        }
    }

    val outputFolderPicker = rememberFolderPicker(
        onSuccess = { uri ->
            scope.launch {
                settingsInteractor.setSaveFolderUri(uri.toString())
            }
            showOutputFolderDialog = false
        }
    )

    var requestedOnce by rememberSaveable {
        mutableStateOf(false)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !requestedOnce) {
            val notAllowed = listOf(
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            ).filter { !context.hasPermissionAllowed(it) }

            if (notAllowed.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    context,
                    buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.READ_MEDIA_IMAGES)
                        }
                        addAll(notAllowed)
                    }.toTypedArray(),
                    0
                )
            }
            requestedOnce = true
        }
    }

    EnhancedAlertDialog(
        visible = showDialog,
        onDismissRequest = { },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null
            )
        },
        title = { Text(stringResource(R.string.permission)) },
        text = {
            Text(stringResource(R.string.permission_sub))
        },
        confirmButton = {
            EnhancedButton(
                onClick = {
                    scope.launch {
                        context.requestStoragePermission()
                    }
                }
            ) {
                Text(stringResource(id = R.string.grant))
            }
        }
    )

    EnhancedAlertDialog(
        visible = showOutputFolderDialog,
        onDismissRequest = { showOutputFolderDialog = false },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null
            )
        },
        title = { Text(stringResource(R.string.default_output_folder)) },
        text = {
            Text("Choose a default output folder so imported Sony camera photos can be saved immediately.")
        },
        confirmButton = {
            EnhancedButton(
                onClick = {
                    outputFolderPicker.pickFolder()
                }
            ) {
                Text(stringResource(id = R.string.gallery_action_select))
            }
        }
    )
}