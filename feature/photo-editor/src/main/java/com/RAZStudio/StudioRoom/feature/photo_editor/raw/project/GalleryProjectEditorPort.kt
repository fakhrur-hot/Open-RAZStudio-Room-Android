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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.project

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarResolver

/**
 * What [com.RAZStudio.StudioRoom.feature.photo_editor.presentation.raw.RawEditorComponent]
 * needs from Gallery Workspace when it opens a photo carrying [ProjectPhotoRef] context
 * (Requirement 9, 15).
 *
 * Declared here (photo-editor) and implemented in feature/gallery-workspace, which already
 * depends on photo-editor — Gradle's dependency direction only runs one way, so the editor
 * cannot import gallery-workspace's `PhotoDao`/`EditDao`/`ProjectSidecarResolver` directly.
 * Hilt's graph is not constrained by that direction: gallery-workspace's binding module is
 * still visible from the app-level component both modules feed into. `SidecarResolver` itself
 * (used by [resolverFor]) is the exact same pattern, already proven by task 4/5's
 * `ProjectSidecarResolver`.
 */
interface GalleryProjectEditorPort {

    /**
     * The [SidecarResolver] for one project photo — beside the original when a folder grant
     * covers it, the per-project fallback file otherwise (and, either way, keeps the `edits`
     * row's location/has-edits bookkeeping in step). [displayName] is needed only to name a
     * freshly created SAF sibling document.
     */
    fun resolverFor(projectId: Long, photoId: Long, displayName: String): SidecarResolver

    /**
     * True when this photo already has a committed Edit_Sidecar (an `edits` row exists).
     * A pure read with NO side effect — unlike calling [resolverFor]'s resolver, which may
     * create the row as a consequence of resolving a location. Used to decide "skip the
     * dialog and open directly" vs "show the first-open selector" (Requirement 15.1–15.3)
     * without prematurely creating a row for a photo nobody has configured yet.
     */
    suspend fun hasEditRecord(projectId: Long, photoId: Long): Boolean

    /**
     * Create the Edit_Sidecar for a first-open commit: [config] (typically lens-correction
     * only) with an empty `UserMacro`, not lighting the has-edits indicator (Requirement
     * 15.17, 15.18). Overwrites nothing — a caller should have already checked
     * [hasEditRecord] is false.
     */
    suspend fun createInitialSidecar(projectId: Long, photoId: Long, displayName: String, config: WorkspaceConfig)

    /** Outcome of [applyLensProfileToMatching] — how many photos got the profile vs. didn't. */
    data class MatchResult(val covered: Int, val skipped: Int)

    /**
     * Requirement 15.11–15.13 — propagate [config]'s lens-correction fields as the initial
     * sidecar (empty macro, per [createInitialSidecar]) onto every OTHER un-edited photo in
     * [projectId] whose EXIF camera + lens matches [sourcePhotoId]'s. Photos that already
     * carry an Edit_Sidecar, or whose EXIF doesn't match, are skipped and counted.
     */
    suspend fun applyLensProfileToMatching(
        projectId: Long,
        sourcePhotoId: Long,
        config: WorkspaceConfig,
    ): MatchResult

    /**
     * Replace [photoId]'s grid thumbnail with [edited] — a render of the
     * photo's CURRENT edit as the editor shows it. Writes the same
     * `thumbs/<photoId>.jpg` the thumbnail worker owns and bumps the row's
     * `generatedAt`, which the grid uses as its image-cache key, so the tile
     * updates the moment the user returns (task 9.4, previously a no-op).
     * The caller keeps ownership of [edited].
     */
    suspend fun updateThumbnail(projectId: Long, photoId: Long, edited: android.graphics.Bitmap)
}
