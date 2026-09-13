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

package com.RAZStudio.StudioRoom.feature.canon_sync.domain.models

import android.net.Uri

/**
 * UI-facing notifications emitted by [com.RAZStudio.StudioRoom.feature.canon_sync.domain.CaptureCoordinator].
 *
 * These flow through a [kotlinx.coroutines.flow.MutableSharedFlow] for status
 * pills, file-count tiles, and progress notifications. Dropping is **fine**
 * here — losing a Progress update just means a slightly stale progress bar.
 * The capture-decision channel (`client.events` → coordinator) is separately
 * Channel.UNLIMITED and never drops; this flow is the lossy fan-out layer.
 */
sealed interface CaptureEvent {

    /** The PTP object handle for the shot this event refers to. */
    val handle: Int

    /** Camera-pushed shot accepted by [com.RAZStudio.StudioRoom.feature.canon_sync.domain.FormatFilter]. */
    data class Started(
        override val handle: Int,
        val filename: String,
        val expectedSizeBytes: Long,
    ) : CaptureEvent

    /** Periodic progress as bytes flow into the destination sink. */
    data class Progress(
        override val handle: Int,
        val bytesWritten: Long,
        val expectedSizeBytes: Long,
    ) : CaptureEvent

    /** Shot finished and the staging file was renamed to its final Uri. */
    data class Completed(
        override val handle: Int,
        val finalFilename: String,
        val finalUri: Uri,
        val bytesWritten: Long,
    ) : CaptureEvent

    /**
     * Shot dropped silently because the user's format filter excluded it
     * (e.g., user picked RAW-only but the camera also shot a JPEG sidecar).
     * Surfaced for diagnostics; the UI doesn't have to display it.
     */
    data class FilteredOut(
        override val handle: Int,
        val format: Int,
    ) : CaptureEvent

    /** Shot failed mid-transfer; staging file was discarded. */
    data class Failed(
        override val handle: Int,
        val filename: String,
        val reason: Reason,
    ) : CaptureEvent {

        sealed interface Reason {
            /** Camera dropped Wi-Fi or returned an error response code. */
            data class Transport(val message: String) : Reason

            /** END_DATA_PACKET arrived but byte count diverged from advertisement. */
            data class PartialPayload(val advertised: Long, val written: Long) : Reason

            /** Camera responded non-OK to GetObject. */
            data class PtpError(val responseCode: Int) : Reason

            /** SAF target threw on open / finalize. */
            data class TargetIoError(val message: String) : Reason
        }
    }
}
