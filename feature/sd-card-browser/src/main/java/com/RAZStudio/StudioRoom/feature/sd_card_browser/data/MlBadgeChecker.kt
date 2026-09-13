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

package com.RAZStudio.StudioRoom.feature.sd_card_browser.data

import com.RAZStudio.StudioRoom.feature.sd_card_browser.domain.FileHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks for ML/ directory presence at volume root once per session.
 * Result is cached until [invalidate] is called (on volume change / unmount / remount).
 */
@Singleton
class MlBadgeChecker @Inject constructor() {

    private var cachedResult: Boolean? = null

    /**
     * Check if [volumeRoot] contains an ML/ directory (case-insensitive).
     * Result is cached — subsequent calls return the cached value until [invalidate] is called.
     */
    suspend fun isMlPresent(volumeRoot: FileHandle): Boolean {
        cachedResult?.let { return it }
        val result = volumeRoot.listChildren().any { it.isDirectory && it.name.equals("ML", ignoreCase = true) }
        cachedResult = result
        return result
    }

    /** Reset cached state (called on volume change / unmount / remount). */
    fun invalidate() {
        cachedResult = null
    }
}
