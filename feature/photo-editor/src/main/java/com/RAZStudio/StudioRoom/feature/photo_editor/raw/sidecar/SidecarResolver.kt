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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Where a photo's sidecar lives.
 *
 * A sealed type rather than a plain [File] because "beside the original" is not
 * always a filesystem path. When the original came through the Storage Access
 * Framework, its directory is reachable only as a *document* — you cannot open a
 * `File` for a sibling of a `content://` URI, and a tree grant gives you
 * `DocumentsContract`, not a path. Modelling that difference here keeps the
 * platform reality out of the callers.
 */
sealed interface SidecarLocation {

    /** True when this sits next to the original photo rather than in app storage. */
    val besideOriginal: Boolean

    /** A real filesystem path: app-private storage, or a `file://` source. */
    data class LocalFile(
        val file: File,
        override val besideOriginal: Boolean,
    ) : SidecarLocation

    /** A SAF document, read and written through the ContentResolver. */
    data class SafDocument(val documentUri: Uri) : SidecarLocation {
        override val besideOriginal: Boolean get() = true
    }

    /** No usable location — the caller skips reading and writing entirely. */
    data object None : SidecarLocation {
        override val besideOriginal: Boolean get() = false
    }
}

/**
 * Decides where a photo's sidecar belongs.
 *
 * Extracted so [SidecarStore] stays a component that knows how to read and write
 * XMP and stops knowing what a Gallery Workspace Project is. The two strategies
 * are then independently testable — the interesting cases being "grant present",
 * "grant absent", and "grant revoked between resolve and write" — without having
 * to stand up a database or a project.
 */
interface SidecarResolver {

    /**
     * Where [sourceUri]'s sidecar should be read from and written to.
     *
     * Implementations may touch the filesystem or the ContentResolver, so this is
     * a suspend function; callers already run it off the main thread.
     */
    suspend fun resolve(sourceUri: Uri): SidecarLocation

    /**
     * Called by [SidecarStore] immediately after a full-snapshot write
     * ([SidecarStore.writeSnapshot]) succeeds at [location], reporting whether the written
     * state carries any user-visible edit (excluding the Original sentinel and
     * workspace-default cards).
     *
     * Default no-op — only Gallery Workspace's resolver needs this, to keep its
     * `edits.isNeutral` has-edits bookkeeping in step with what was actually
     * written (Requirement 15.18: a first-open commit with an empty macro must
     * NOT light the has-edits indicator).
     */
    suspend fun onSnapshotWritten(location: SidecarLocation, hasVisibleEdit: Boolean) {}
}

/**
 * Behaviour for the standalone editor, preserved exactly as it was before the
 * resolver was extracted:
 *
 *  1. `<file>.xmp` beside the original when the source is a `file://` URI whose
 *     directory is writable.
 *  2. Otherwise `cacheDir/sidecars/<sha>.xmp`.
 *
 * The cache fallback is deliberately retained HERE. It is evictable, which is
 * unacceptable for a library photo (Requirement 4.10 forbids it for Project
 * sidecars) but is the long-standing behaviour for a one-off file opened
 * directly, and changing that is not this task's job.
 */
class DefaultSidecarResolver(private val context: Context) : SidecarResolver {

    override suspend fun resolve(sourceUri: Uri): SidecarLocation {
        nextToSource(sourceUri)?.let { return SidecarLocation.LocalFile(it, besideOriginal = true) }
        return SidecarLocation.LocalFile(cacheFallback(sourceUri), besideOriginal = false)
    }

    private fun nextToSource(sourceUri: Uri): File? {
        if (sourceUri.scheme != "file") return null
        val path = sourceUri.path ?: return null
        val source = File(path)
        if (!source.parentFile.let { it != null && it.canWrite() }) return null
        return File(source.parentFile, source.name + ".xmp")
    }

    private fun cacheFallback(sourceUri: Uri): File {
        val sha = sha256(sourceUri.toString())
        return File(File(context.cacheDir, "sidecars"), "$sha.xmp")
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
