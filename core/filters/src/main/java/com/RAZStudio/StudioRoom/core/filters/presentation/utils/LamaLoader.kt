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

package com.RAZStudio.StudioRoom.core.filters.presentation.utils

import com.RAZStudio.StudioRoom.core.domain.remote.DownloadProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// LaMa inpainting removed (lib:neural-tools deleted). Stub satisfies compile-time references.
interface LamaLoader {
    val isDownloaded: Boolean
    fun download(): Flow<DownloadProgress>

    companion object Companion : LamaLoader by LamaLoaderImpl
}

private object LamaLoaderImpl : LamaLoader {
    override val isDownloaded: Boolean = false
    override fun download(): Flow<DownloadProgress> = emptyFlow()
}
