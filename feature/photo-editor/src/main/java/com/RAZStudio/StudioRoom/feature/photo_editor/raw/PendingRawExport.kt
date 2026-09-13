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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata

/**
 * One-shot bridge between [RawPipelineCoordinator] and [PhotoEditorComponent].
 *
 * When the user clicks "Apply to Editor" in the RAW editor, we navigate immediately
 * with the already-rendered preview bitmap (fast, small). The full-res render
 * (MacroProcessor on a multi-megapixel image) is deferred to save time via [fn].
 *
 * [fullResWidth]/[fullResHeight] are the actual pixel dimensions of the full-res
 * output file — set before navigation so PhotoEditorComponent can initialise
 * imageInfo at full-res dimensions instead of preview dimensions.
 *
 * [fn] is set by the coordinator just before navigation and consumed (cleared) by
 * PhotoEditorComponent.saveBitmap on first save.  The result path is cached in
 * PhotoEditorComponent so repeated saves reuse the same full-res file.
 *
 * [rawMetadata] carries the original camera EXIF so PhotoEditorComponent can
 * populate its exif state directly without re-reading the nav-preview PNG.
 */
internal object PendingRawExport {
    @Volatile var fullResWidth: Int = 0
    @Volatile var fullResHeight: Int = 0
    @Volatile var fn: (suspend () -> String?)? = null
    @Volatile var rawMetadata: RawMetadata? = null

    fun set(width: Int, height: Int, metadata: RawMetadata?, block: suspend () -> String?) {
        fullResWidth = width
        fullResHeight = height
        rawMetadata = metadata
        fn = block
    }
    fun consume(): (suspend () -> String?)? = fn.also { fn = null }
    fun clear() { fn = null; fullResWidth = 0; fullResHeight = 0; rawMetadata = null }
}
