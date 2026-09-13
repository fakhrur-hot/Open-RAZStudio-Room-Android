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

package com.RAZStudio.StudioRoom.app.presentation.components.functions

import com.RAZStudio.StudioRoom.app.presentation.components.StudioRoomApplication
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.ui.utils.helper.DeviceInfo
import com.RAZStudio.StudioRoom.core.utils.Logger
import com.RAZStudio.StudioRoom.core.utils.attachLogWriter


internal fun StudioRoomApplication.attachLogWriter() {
    Logger.attachLogWriter(
        context = this@attachLogWriter,
        fileProvider = getString(R.string.file_provider),
        logsFilename = "studio_room_logs.txt",
        startupLog = Logger.Log(
            tag = "Device Info",
            message = "--${DeviceInfo.get()}--",
            level = Logger.Level.Info
        ),
        errorHandler = analyticsManager::sendReport,
        isSyncCreate = false
    )
}