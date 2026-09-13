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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.importer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Launches ACTION_OPEN_DOCUMENT with allowMultiple = true and a MIME filter
 * covering every format that detectSourceFormat() recognises (Requirement 3.1).
 *
 * The primary intent type is the image wildcard MIME type so the SAF picker
 * shows all image files including RAW formats whose vendor MIME types a given
 * file manager may surface as vendor-specific subtypes. The specific list is
 * passed as Intent.EXTRA_MIME_TYPES for pickers that honour it, narrowing the
 * displayed types to exactly the formats this library understands while keeping
 * the wildcard as a safety net.
 *
 * Usage from a Composable:
 *
 *   val launcher = photoImporter.rememberLauncher { uris ->
 *       // handle the picked URIs
 *   }
 *   Button(onClick = { launcher.launch(photoImporter.buildIntent()) }) { ... }
 *
 * The class is a @Singleton so it is injectable anywhere inside the
 * feature/gallery-workspace component graph without needing Composable context.
 * The rememberLauncher helper is the only Composable-scoped API; everything
 * else is pure logic.
 */
@Singleton
class PhotoImporter @Inject constructor() {

    /**
     * Build an ACTION_OPEN_DOCUMENT intent that:
     * - allows the user to select multiple documents via EXTRA_ALLOW_MULTIPLE;
     * - sets the primary MIME type to the image wildcard; and
     * - supplies IMPORT_MIME_TYPES via EXTRA_MIME_TYPES for pickers that filter
     *   their file list by the declared types.
     *
     * Requirement 3.1: every format that detectSourceFormat() accepts is
     * reachable through this intent.
     */
    fun buildIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        // `*/*` rather than `image/*` — this is the documented pattern when
        // filtering by several MIME types, and it is load-bearing here for a
        // reason we already hit in the share flow: plenty of file managers and
        // cloud providers report a RAW as `application/octet-stream`. With
        // `type = "image/*"` those files are INVISIBLE in the picker, so a user
        // could not import their own NEFs. EXTRA_MIME_TYPES does the narrowing.
        type = MIME_ANY
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(Intent.EXTRA_MIME_TYPES, IMPORT_MIME_TYPES)
        // Ask for a grant that CAN be persisted. Without
        // FLAG_GRANT_PERSISTABLE_URI_PERMISSION the returned grant dies with the
        // process and `takePersistableUriPermission` has nothing to take — the
        // library would be full of unreachable rows on the next cold start,
        // which is the failure mode Requirement 3.2 exists to prevent.
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

    /**
     * Register an activity-result launcher in the current Composable scope that
     * invokes onResult with the list of URIs the user selected, or an empty list
     * when the picker was dismissed without a selection.
     *
     * Follows the same StartActivityForResult pattern used throughout this app
     * (see core/ui/.../content_pickers/ImagePicker.kt), which collects URIs
     * from both Intent.data (single selection) and Intent.clipData (multi).
     */
    @Composable
    fun rememberLauncher(
        onResult: (List<Uri>) -> Unit,
    ): ManagedActivityResultLauncher<Intent, ActivityResult> =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = { result ->
                val intent = result.data
                // Extract raw document URIs from the SAF result without copying
                // to cache. The app-wide ClipData.clipList() in ClipboardUtils
                // redirects non-file-provider URIs to cache, which is wrong here
                // where we receive persistable SAF document URIs.
                val uris: List<Uri> = intent?.clipData?.rawUriList()
                    ?: intent?.data?.let { listOf(it) }
                    ?: emptyList()
                onResult(uris)
            }
        )

    companion object {

        // Kept as a constant so the buildIntent() KDoc can reference it without
        // embedding the wildcard pattern inside a block comment, since Kotlin
        // parses /* inside block comments as a nested comment opener.
        private const val MIME_IMAGE_WILDCARD = "image/*"

        /** Broadest primary type; the actual narrowing is EXTRA_MIME_TYPES. */
        private const val MIME_ANY = "*/*"

        /**
         * Every MIME type accepted by the formats that detectSourceFormat() handles.
         *
         * Derived from the private detectSourceFormat function in
         * WorkspaceSelectorSheet.kt; the mapping is replicated here independently
         * so feature/gallery-workspace does not need to depend on
         * feature/photo-editor for this list.
         *
         * The trailing wildcard entry is a safety net for RAW variants with
         * vendor MIME types not yet in the explicit list.
         */
        val IMPORT_MIME_TYPES: Array<String> = arrayOf(
            // Standard raster formats
            "image/jpeg",
            "image/webp",
            "image/bmp",
            "image/x-bmp",
            "image/png",
            "image/tiff",

            // RAW formats
            "image/x-adobe-dng",     // Adobe / generic DNG
            "image/x-canon-cr2",     // Canon CR2
            "image/x-canon-cr3",     // Canon CR3
            "image/x-nikon-nef",     // Nikon NEF
            "image/x-sony-arw",      // Sony ARW
            "image/x-fuji-raf",      // Fujifilm RAF
            "image/x-panasonic-rw2", // Panasonic RW2
            "image/x-olympus-orf",   // Olympus ORF
            "image/x-pentax-pef",    // Pentax PEF
            "image/x-samsung-srw",   // Samsung SRW
            "image/x-sigma-x3f",     // Sigma X3F
            "image/x-epson-erf",     // Epson ERF
            "image/x-hasselblad-3fr", // Hasselblad 3FR
            "image/x-raw",           // Generic / fallback RAW

            // Safety nets. `image/*` catches vendor RAW subtypes not listed
            // above; `application/octet-stream` catches the very common case of
            // a provider serving a RAW with no meaningful MIME type at all,
            // which is why the primary type is `*/*` and not `image/*`.
            MIME_IMAGE_WILDCARD,
            "application/octet-stream",
        )
    }
}

// Extract all URIs from a ClipData without copying files to the cache directory.
// The app-wide ClipData.clipList() extension in ClipboardUtils.kt redirects
// non-file-provider URIs to cache, appropriate for clipboard paste but wrong
// here where we receive persistable SAF document URIs.
private fun ClipData.rawUriList(): List<Uri> =
    List(itemCount) { index -> getItemAt(index).uri }.filterNotNull()