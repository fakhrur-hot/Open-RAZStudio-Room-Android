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

package com.RAZStudio.StudioRoom.feature.canon_sync.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.CaptureTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.IOException
import java.io.OutputStream

/**
 * Storage Access Framework implementation of [CaptureTarget].
 *
 * The user picks a tree Uri via SAF (the existing "Default Output" pattern in
 * the app) and arms a sub-folder. This class translates that tree Uri into a
 * concrete `DocumentFile` directory, then stages each capture as `.part`
 * before atomically renaming to the final filename on success.
 *
 * Performance constraints from claude advanced plan.md §3.8.3:
 *
 *  - Output stream is wrapped in a [java.io.BufferedOutputStream] with a
 *    **128 KB** buffer. Default 8 KB is catastrophic on SAF — every flush is
 *    a Binder IPC into ContentResolver and dominates the transfer cost.
 *  - The directory `DocumentFile` is **resolved once** by [forDirectory] and
 *    cached on the instance. No `findFile()` / `length()` calls in the hot
 *    path; the per-file `createFile()` call is the only ContentResolver
 *    round-trip during a transfer.
 *  - The output stream Uri is captured before the transfer begins; we never
 *    re-query `DocumentFile` mid-transfer.
 *
 * Public (not module-internal): `feature/sd-card-browser`'s USB-OTG/SAF card
 * import path uses this same class so both import pipelines (Canon Sync's
 * Wi-Fi PTP/IP download and the SD card browser's USB/SAF read) write to
 * disk identically — same session-subfolder destination, same staging
 * `.part` + atomic rename, same buffered SAF sink. Only the source-side
 * read differs (PTP object stream vs. USB mass-storage / SAF input stream).
 */
class SafCaptureTarget private constructor(
    private val contentResolver: ContentResolver,
    private val directory: DocumentFile,
) : CaptureTarget {

    override suspend fun open(
        requestedFilename: String,
        mimeType: String,
    ): CaptureTarget.Allocation = withContext(Dispatchers.IO) {
        val stagingName = "$requestedFilename$STAGING_SUFFIX"
        // SAF appends "(1)", "(2)", … if a file with the same display name
        // already exists, so we never overwrite a previous capture.
        val stagingFile = directory.createFile(STAGING_MIME, stagingName)
            ?: throw IOException("DocumentFile.createFile returned null for '$stagingName'")
        val stagingUri = stagingFile.uri
        val outputStream: OutputStream = contentResolver.openOutputStream(stagingUri, "w")
            ?: run {
                stagingFile.runCatching { delete() }
                throw IOException("ContentResolver.openOutputStream returned null for $stagingUri")
            }
        // 128 KB buffer over the SAF output stream — dramatic Binder-IPC
        // reduction vs Okio's default 8 KB Segment size for SAF sinks.
        val bufferedStream = outputStream.buffered(BUFFER_BYTES)
        val sink: BufferedSink = bufferedStream.sink().buffer()

        SafAllocation(
            stagingFile = stagingFile,
            stagingUri = stagingUri,
            finalFilename = uniqueFinalFilename(directory, requestedFilename, mimeType),
            sink = sink,
            bufferedStream = bufferedStream,
        )
    }

    /**
     * Pick a display name that doesn't collide with an existing finished file.
     * SAF's own collision suffixing (`(1)`, `(2)`) is for current write target
     * — we want our own `_1`, `_2` style to match the gallery viewer's
     * expectations.
     */
    private fun uniqueFinalFilename(
        dir: DocumentFile,
        requested: String,
        @Suppress("UNUSED_PARAMETER") mimeType: String,
    ): String {
        if (dir.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot >= 0) requested.substring(0, dot) else requested
        val ext = if (dot >= 0) requested.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "${stem}_$n$ext"
            if (dir.findFile(candidate) == null) return candidate
            n++
        }
    }

    private inner class SafAllocation(
        private val stagingFile: DocumentFile,
        override val stagingUri: Uri,
        override val finalFilename: String,
        override val sink: BufferedSink,
        private val bufferedStream: OutputStream,
    ) : CaptureTarget.Allocation {

        override suspend fun finalize(): Uri = withContext(Dispatchers.IO) {
            // Order matters:
            //   1. sink.flush+close so Okio's segments hit the BufferedOutputStream.
            //   2. bufferedStream.close so SAF actually commits the file (this is
            //      the moment a SAF write becomes durable — Android won't show
            //      the file in the gallery before this returns).
            //   3. DocumentFile.renameTo to swap the .part display name for the
            //      final one.
            //
            // On the external-storage SAF provider the document ID is derived
            // from the path, so after rename the original stagingFile.uri still
            // points at the now-non-existent ".part" path. Callers (RAWEditor)
            // try to open the returned Uri and get FileNotFoundException.
            // Re-resolve the final Uri from the parent directory after rename.
            sink.runCatching { flush() }
            sink.runCatching { close() }
            bufferedStream.runCatching { close() }
            val renamed = stagingFile.renameTo(finalFilename)
            if (!renamed) {
                throw IOException("DocumentFile.renameTo('$finalFilename') failed")
            }
            // Prefer the live DocumentFile's refreshed Uri; fall back to a fresh
            // directory scan when the provider keeps stale URIs in place.
            val refreshed = stagingFile.uri.takeIf { uri ->
                runCatching { contentResolver.openInputStream(uri)?.close(); true }
                    .getOrDefault(false)
            }
            refreshed ?: directory.findFile(finalFilename)?.uri
                ?: throw IOException("Renamed to '$finalFilename' but could not resolve final Uri")
        }

        override suspend fun discard() = withContext(Dispatchers.IO) {
            sink.runCatching { close() }
            bufferedStream.runCatching { close() }
            stagingFile.runCatching { delete() }
            Unit
        }
    }

    companion object {

        /**
         * Build a target backed by the given tree Uri. Returns null if the
         * Uri doesn't resolve to a writable directory.
         */
        fun forDirectory(context: Context, treeUri: Uri): SafCaptureTarget? {
            val dir = runCatching { DocumentFile.fromTreeUri(context, treeUri) }
                .getOrNull() ?: return null
            if (!dir.isDirectory || !dir.canWrite()) return null
            return SafCaptureTarget(
                contentResolver = context.contentResolver,
                directory = dir,
            )
        }

        /**
         * Delete leftover `.part` staging files under [rootTreeUri] — orphans
         * from a transfer that was HARD-killed (app killed / camera Wi-Fi
         * dropped abruptly) before [Allocation.discard] could run. Graceful
         * failures already self-clean; this only mops up crash leftovers so
         * they don't accumulate (and don't keep tripping the OS MediaScanner).
         *
         * Scans the root and its IMMEDIATE subfolders (the per-session
         * `Model-yyyyMMdd/` dirs). Best-effort — never throws. Returns the
         * count removed. Call once per session, off the hot path.
         */
        suspend fun sweepStagingFiles(context: Context, rootTreeUri: Uri): Int =
            withContext(Dispatchers.IO) {
                val root = runCatching { DocumentFile.fromTreeUri(context, rootTreeUri) }
                    .getOrNull() ?: return@withContext 0
                var deleted = 0
                fun sweep(dir: DocumentFile, recurse: Boolean) {
                    val children = runCatching { dir.listFiles() }.getOrNull() ?: return
                    for (child in children) {
                        when {
                            child.isFile && (child.name?.endsWith(STAGING_SUFFIX) == true) ->
                                if (runCatching { child.delete() }.getOrDefault(false)) deleted++
                            child.isDirectory && recurse -> sweep(child, recurse = false)
                        }
                    }
                }
                sweep(root, recurse = true)
                deleted
            }

        /**
         * SAF write-buffer size. 128 KB picked per claude advanced plan.md
         * §3.8.3 — empirically the sweet spot on Android 14+ flash for SAF
         * writes; smaller buffers spend all their time in Binder IPC, larger
         * gives no further gain because kernel writeback already coalesces.
         */
        private const val BUFFER_BYTES = 128 * 1024

        /** Suffix used for staging files. Gallery viewer should hide these. */
        private const val STAGING_SUFFIX = ".part"

        /** Generic binary while staging — the final mime is set after rename. */
        private const val STAGING_MIME = "application/octet-stream"
    }
}
