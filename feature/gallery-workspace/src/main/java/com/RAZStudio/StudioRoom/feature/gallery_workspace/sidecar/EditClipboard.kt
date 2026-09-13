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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar

import androidx.compose.runtime.mutableStateOf
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot

/**
 * The Gallery Workspace edit-settings clipboard (Requirements 14.12–14.13, 14.20).
 *
 * Session-held and IN-MEMORY ONLY — discarded on process death by design
 * (Req 14.12e): a clipboard that silently survives days-old sessions pastes
 * stale looks nobody remembers copying.
 *
 * Snapshot-state backed so the tile "copied settings" indicator and the
 * menu/action-bar enable states recompose the moment a copy or clear happens
 * (same lesson as the RAW editor's RawSettingsClipboard: plain vars don't
 * recompose anything).
 *
 * Distinct from the editor's `RawSettingsClipboard` on purpose: that one moves
 * LIVE action stacks between open editor sessions; this one moves SIDECAR
 * snapshots between library photos and carries the source-kit metadata the
 * paste sheet's mismatch warnings need (Req 14.12a).
 */
object EditClipboard {

    /**
     * @property cameraName EXIF Make+Model of the source, empty when unknown.
     * @property lensName   EXIF LensModel of the source, empty when unknown.
     * @property aspect     Long-side / short-side ratio of the source pixels —
     *                      orientation-agnostic, for the crop mismatch rule.
     */
    data class Entry(
        val photoId: Long,
        val projectId: Long,
        val displayName: String,
        val snapshot: SidecarSnapshot,
        val cameraName: String,
        val lensName: String,
        val aspect: Float,
    )

    private val state = mutableStateOf<Entry?>(null)

    /** Current clipboard content; null = empty (paste entries are absent). */
    val entry: Entry? get() = state.value

    val isEmpty: Boolean get() = state.value == null

    /** The photo whose tile shows the persistent "copied settings" indicator. */
    val sourcePhotoId: Long? get() = state.value?.photoId

    fun set(entry: Entry) { state.value = entry }

    /** "Clear copied settings" (Req 14.12d) — without it the state is sticky. */
    fun clear() { state.value = null }
}
