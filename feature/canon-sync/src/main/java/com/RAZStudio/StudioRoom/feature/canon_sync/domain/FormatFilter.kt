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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain

import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode
import com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants

/**
 * Decides whether a given PTP object format code matches the user's
 * [FormatMode] selection. Pure function — the coordinator queries this once
 * per shutter event and skips the download if [accept] returns false.
 *
 * "Other" RAW formats (Nikon NEF, Sony ARW, Fuji RAF) are not in v1 scope but
 * are documented here so the filter doesn't need a rewrite when they land:
 * for now [accept] only matches Canon's CR2/CR3 against [FormatMode.RAW].
 */
internal object FormatFilter {

    fun accept(format: Int, mode: FormatMode): Boolean = when (mode) {
        FormatMode.RAW -> format in PtpIpConstants.RAW_FORMATS
        FormatMode.JPEG -> format == PtpIpConstants.FMT_JPEG
        FormatMode.RAW_AND_JPEG -> format in PtpIpConstants.RAW_FORMATS || format == PtpIpConstants.FMT_JPEG
    }
}
