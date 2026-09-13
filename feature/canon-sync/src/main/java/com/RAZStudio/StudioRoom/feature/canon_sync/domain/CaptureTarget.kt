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

import android.net.Uri
import okio.BufferedSink

/**
 * Abstracts file creation from CaptureCoordinator. The coordinator itself
 * stays free of Android storage primitives so it's straightforward to test
 * with an in-memory target.
 *
 * Lifecycle is intentionally three-step (open → finalize OR discard) so the
 * implementation can stage a `.part` write and atomically rename on success:
 *
 *   1. [open] — implementation creates a placeholder file (e.g. `IMG_0001.CR3.part`),
 *      returns an open [Allocation] carrying the staging Uri and a writable sink.
 *   2. Coordinator writes the camera's bytes into [Allocation.sink].
 *   3. Coordinator calls one of:
 *      - [Allocation.finalize] — implementation closes the sink and renames
 *        the staging file to its final name (`IMG_0001.CR3`). Returns the
 *        final Uri the rest of the app should display.
 *      - [Allocation.discard] — implementation closes the sink and deletes
 *        the staging file. Used on transport error / partial payload.
 *
 * The staging-then-rename pattern means a crash or interrupted transfer
 * leaves a `.part` on disk that's obviously distinct from a finished frame;
 * the gallery viewer can hide `.part` files and a future cleanup pass can
 * sweep them safely.
 *
 * Public (not module-internal): shared with `feature/sd-card-browser` so
 * both the Wi-Fi (PTP/IP) and USB-OTG/SAF card-reader import paths write
 * through the exact same staging/rename/SAF-sink logic — the only
 * difference between the two pipelines is where the source bytes come
 * from, never how they're written to disk.
 */
interface CaptureTarget {

    /**
     * Allocate a staging file for [requestedFilename]. If a previous capture
     * already created the same filename the implementation should append a
     * `_1`, `_2` … suffix (camera filename collisions can happen when two
     * sessions in a row both started at IMG_0001).
     */
    suspend fun open(requestedFilename: String, mimeType: String): Allocation

    /**
     * Lifecycle handle returned by [CaptureTarget.open]. The coordinator must
     * call exactly one of [finalize] or [discard] when the transfer ends.
     */
    interface Allocation {
        /** The staging Uri (e.g. content://…/IMG_0001.CR3.part). */
        val stagingUri: Uri

        /** The final filename the file will have after [finalize]. */
        val finalFilename: String

        /** Caller writes camera bytes here. */
        val sink: BufferedSink

        /** Promote the staging file to its final name. Returns the final Uri. */
        suspend fun finalize(): Uri

        /** Throw the staging file away. Best-effort — never fails the caller. */
        suspend fun discard()
    }
}
