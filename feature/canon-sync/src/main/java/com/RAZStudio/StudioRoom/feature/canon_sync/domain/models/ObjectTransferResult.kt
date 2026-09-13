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

/**
 * Outcome of a single GetObject (0x1009) data-phase transfer.
 *
 * [Success] indicates the camera streamed the full payload, the sink received
 * every byte, and the OperationResponse came back with PTP_RC_OK (0x2001).
 *
 * Failure variants are deliberately narrow so the caller can react precisely:
 * [PartialPayload] means the END_DATA_PACKET arrived but the byte count didn't
 * match what START_DATA_PACKET advertised (a Canon firmware bug or wire
 * corruption); [PtpResponseError] means the wire transfer itself looked fine
 * but the camera returned a non-OK response code; [TransportError] is for
 * everything else (socket closed mid-transfer, etc.).
 *
 * The sink is **not** closed by streamObjectTo regardless of outcome — the
 * caller owns its lifecycle (especially important for SAF DocumentFile output
 * streams, where premature close interferes with the ContentResolver session).
 */
sealed interface ObjectTransferResult {

    /** Total bytes the camera declared in START_DATA_PACKET. */
    val advertisedSizeBytes: Long

    /** Total bytes actually written to the sink. */
    val bytesWritten: Long

    data class Success(
        override val advertisedSizeBytes: Long,
        override val bytesWritten: Long,
    ) : ObjectTransferResult

    sealed interface Failure : ObjectTransferResult {

        data class PartialPayload(
            override val advertisedSizeBytes: Long,
            override val bytesWritten: Long,
        ) : Failure

        data class PtpResponseError(
            override val advertisedSizeBytes: Long,
            override val bytesWritten: Long,
            val responseCode: Int,
        ) : Failure

        data class TransportError(
            override val advertisedSizeBytes: Long,
            override val bytesWritten: Long,
            val message: String,
        ) : Failure
    }
}
