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

package com.RAZStudio.collages.model

import android.net.Uri
import com.RAZStudio.collages.utils.ParamsManager
import com.RAZStudio.collages.view.PhotoItem

internal data class CollageLayout(
    val preview: Uri,
    val title: String,
    val paramsManager: ParamsManager? = null,
    val photoItemList: List<PhotoItem> = emptyList()
)