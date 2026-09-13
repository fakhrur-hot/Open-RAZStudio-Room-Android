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

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest

/**
 * Identity hint for a photo file (Requirements 3.4a–3.4c).
 *
 * Format: `"<sizeBytes>:<sha256 of first 64 KB>:<sha256 of last 64 KB>"`.
 *
 * **Deliberately NOT a whole-file hash.** A 500-photo project of 25 MB RAWs
 * would be 12.5 GB of reads at import, and every relink scan would have to
 * re-read candidates the same way. The partial form reads at most 128 KB per
 * file — roughly 64 MB for that same project — and stays highly distinguishing
 * for camera originals, because a RAW's first 64 KB carries its header, EXIF,
 * maker notes and usually the start of the embedded preview. The trailing window
 * catches truncation and re-encoding.
 *
 * This is an identity HINT, not a cryptographic guarantee (Requirement 3.4b).
 * Two files sharing a byte size and both 64 KB windows collide; for real camera
 * files that effectively means they are the same file. Never use it for a
 * security or integrity decision.
 *
 * What it buys over filename matching: relinking a file that has been RENAMED,
 * and disambiguating two same-named candidates in a relinked directory
 * (Requirements 10.7b, 10.7c).
 */
object ContentFingerprint {

    const val WINDOW_BYTES = 64 * 1024

    /**
     * Compute the fingerprint for [uri], or null when it cannot be read
     * (Requirement 3.4c — a null fingerprint is imported normally and simply
     * falls back to filename matching wherever the fingerprint would be used).
     */
    fun compute(resolver: ContentResolver, uri: Uri, sizeBytes: Long): String? =
        runCatching {
            if (sizeBytes <= 0L) return null

            val head = resolver.openInputStream(uri)?.use { it.readAtMost(WINDOW_BYTES) }
                ?: return null

            // For a file at or under one window, head IS the whole file; hashing
            // a second window would just repeat it.
            val tail = if (sizeBytes <= WINDOW_BYTES) {
                head
            } else {
                resolver.openInputStream(uri)?.use { stream ->
                    stream.skipExactly(sizeBytes - WINDOW_BYTES)
                    stream.readAtMost(WINDOW_BYTES)
                } ?: return null
            }

            "$sizeBytes:${head.sha256()}:${tail.sha256()}"
        }.getOrNull()

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it) }

    /** Read up to [max] bytes; a short read is fine and yields a shorter array. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val buf = ByteArray(max)
        var filled = 0
        while (filled < max) {
            val n = read(buf, filled, max - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled == max) buf else buf.copyOf(filled)
    }

    /**
     * `InputStream.skip` may skip fewer bytes than asked without being at EOF,
     * so loop. Returns early if the stream ends, which the caller detects as a
     * short tail read.
     */
    private fun InputStream.skipExactly(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // skip() made no progress — fall back to reading through.
                if (read() < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
