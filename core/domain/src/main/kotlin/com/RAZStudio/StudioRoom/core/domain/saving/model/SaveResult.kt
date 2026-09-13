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

package com.RAZStudio.StudioRoom.core.domain.saving.model

sealed class SaveResult(
    open val savingPath: String
) {

    data class Success(
        val message: String? = null,
        override val savingPath: String,
        val isOverwritten: Boolean = false,
        /**
         * True when the user's configured save folder was unavailable (stale /
         * revoked SAF grant, common after a reinstall) so the save fell back to
         * the default folder. The folder setting has been reset to default; UIs
         * should surface a one-time notice so the user can re-pick it.
         */
        val savedToFallbackFolder: Boolean = false,
        /**
         * Content/document URI of the file that was actually written (null for
         * clipboard-only saves). Lets export UIs offer "share the saved photo"
         * without re-querying MediaStore for the newest image.
         */
        val savedUri: String? = null,
    ) : SaveResult(savingPath)

    sealed class Error(open val throwable: Throwable) : SaveResult("") {
        data object MissingPermissions : Error(IllegalAccessException("MissingPermissions"))

        data class Exception(
            override val throwable: Throwable
        ) : Error(throwable)
    }

    data class Skipped(
        val uri: String? = null
    ) : SaveResult("")

    fun onSuccess(
        action: () -> Unit
    ): SaveResult = apply {
        if (this is Success) action()
    }
}

fun List<SaveResult>.onSuccess(
    action: () -> Unit
): List<SaveResult> = apply {
    if (this.any { it is SaveResult.Success }) action()
}