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

import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.CaptureEvent
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.FormatMode
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.ObjectTransferResult
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.PtpEvent
import com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonWifiClient
import com.RAZStudio.StudioRoom.feature.canon_sync.net.PtpIpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The single consumer of the camera's event channel
 * ([CanonWifiClient.events]). Drives the capture pipeline end-to-end:
 *
 *   shutter event → format filter → SAF target.open → streamObjectTo → finalize
 *
 * Concurrency invariants (claude advanced plan.md §3.2, §3.8.4):
 *
 *  - **Exactly one consumer** of the client event channel. The coordinator's
 *    `for (event in events)` loop serialises everything else downstream.
 *  - **No concurrent `streamObjectTo`** because Canon firmware rejects
 *    overlapping operations with PTP_RC_DeviceBusy (0x2019). The client's
 *    internal commandMutex enforces this, but we don't even try to parallelise
 *    transfers — the coordinator processes one event at a time.
 *  - **Dedupe** via [seenHandles] so a body that fires both
 *    [PtpEvent.EosObjectAddedEx] and [PtpEvent.ObjectAdded] for the same shot
 *    doesn't trigger two downloads. Whichever event arrives first wins; the
 *    second is silently dropped.
 *  - **Atomic finalize**: every transfer writes to a `.part` staging file
 *    that the SAF target only renames to the final filename on
 *    [ObjectTransferResult.Success]. A crash or partial-payload failure
 *    leaves a `.part` on disk that the gallery viewer hides; no half-written
 *    file is ever visible under its real name.
 *
 * UI subscribes to [events] (a [SharedFlow]) for status pills, progress
 * notifications, and tile file-count updates. Dropping is acceptable here —
 * losing a Progress update just means a slightly stale bar. The capture
 * decision path (`client.events` → coordinator) is separately
 * Channel.UNLIMITED and never drops.
 */
internal class CaptureCoordinator(
    private val client: CanonWifiClient,
    private val source: ReceiveChannel<PtpEvent>,
    private val target: CaptureTarget,
    private val mode: () -> FormatMode,
) {

    /**
     * Handles we've already started a download for. Stops the
     * EosObjectAddedEx → ObjectAdded duplicate from queueing a second
     * transfer. Cleared on session restart (when the coordinator is
     * re-instantiated by the next [start]).
     */
    private val seenHandles: MutableSet<Int> = HashSet()

    private val _events = MutableSharedFlow<CaptureEvent>(
        replay = 0,
        extraBufferCapacity = UI_FLOW_BUFFER,
        // Dropping is fine here — it's the lossy UI fan-out, not the capture
        // decision path. The decision path is upstream of this and runs on
        // Channel.UNLIMITED, where dropping would lose photos.
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        for (event in source) handle(event)
    }

    private suspend fun handle(event: PtpEvent) {
        when (event) {
            is PtpEvent.EosObjectAddedEx -> processNew(
                handle = event.handle,
                filename = event.filename,
                format = event.format,
                sizeBytes = event.sizeBytes,
            )
            is PtpEvent.EosObjectAddedEx64 -> processNew(
                handle = event.handle,
                filename = event.filename,
                format = event.format,
                sizeBytes = event.sizeBytes,
            )
            is PtpEvent.ObjectAdded -> processLegacyObjectAdded(event.handle)
            else -> {
                // DeviceInfoChanged, StoreFull, CaptureComplete, ObjectInfoChangedEx,
                // ObjectRemoved, Unknown — capture pipeline doesn't act on these.
                // UI layer may still observe them via client.events directly.
            }
        }
    }

    /**
     * Fallback path for legacy bodies that fire 0x4002 instead of 0xC181.
     * We have to issue an extra GetObjectInfo round-trip to learn filename /
     * format / size before deciding whether to download.
     */
    private suspend fun processLegacyObjectAdded(handle: Int) {
        if (handle in seenHandles) return
        val info = try {
            client.getObjectInfo(handle)
        } catch (io: IOException) {
            seenHandles += handle
            _events.tryEmit(
                CaptureEvent.Failed(
                    handle = handle,
                    filename = "(unknown — handle=$handle)",
                    reason = CaptureEvent.Failed.Reason.Transport(
                        message = io.message ?: io.javaClass.simpleName,
                    ),
                )
            )
            return
        }
        processNew(
            handle = handle,
            filename = info.filename,
            format = info.format,
            sizeBytes = info.sizeBytes,
        )
    }

    private suspend fun processNew(
        handle: Int,
        filename: String,
        format: Int,
        sizeBytes: Long,
    ) {
        if (handle in seenHandles) return
        seenHandles += handle

        if (!FormatFilter.accept(format = format, mode = mode())) {
            _events.tryEmit(CaptureEvent.FilteredOut(handle = handle, format = format))
            return
        }

        val allocation = try {
            target.open(requestedFilename = filename, mimeType = mimeTypeFor(format))
        } catch (io: IOException) {
            _events.tryEmit(
                CaptureEvent.Failed(
                    handle = handle,
                    filename = filename,
                    reason = CaptureEvent.Failed.Reason.TargetIoError(
                        message = io.message ?: io.javaClass.simpleName,
                    ),
                )
            )
            return
        }

        _events.tryEmit(
            CaptureEvent.Started(
                handle = handle,
                filename = allocation.finalFilename,
                expectedSizeBytes = sizeBytes,
            )
        )

        // Use Canon's chunked 0x9107 EOS_GetPartialObject (preferred by EOS
        // Utility per PC_EOSUTILITY2.pcapng) instead of the standard 0x1009
        // GetObject. Legacy 6D / 5D-III / 7D / 60D firmware times out
        // mid-transfer on full-RAW (>50 MB) GetObject responses; looping
        // 1 MB chunks reads the same image reliably. The size from the
        // shutter event (0xC181 record) becomes the progress denominator
        // for the UI without an extra GetObjectInfo round-trip.
        val result = client.streamObjectViaPartial(
            handle = handle,
            destination = allocation.sink,
            advertisedTotalBytes = sizeBytes,
            onProgress = { written, advertised ->
                _events.tryEmit(
                    CaptureEvent.Progress(
                        handle = handle,
                        bytesWritten = written,
                        expectedSizeBytes = if (advertised >= 0) advertised else sizeBytes,
                    )
                )
            },
        )

        when (result) {
            is ObjectTransferResult.Success -> {
                val finalUri = try {
                    allocation.finalize()
                } catch (io: IOException) {
                    allocation.runCatching { discard() }
                    _events.tryEmit(
                        CaptureEvent.Failed(
                            handle = handle,
                            filename = allocation.finalFilename,
                            reason = CaptureEvent.Failed.Reason.TargetIoError(
                                message = io.message ?: io.javaClass.simpleName,
                            ),
                        )
                    )
                    return
                }
                _events.tryEmit(
                    CaptureEvent.Completed(
                        handle = handle,
                        finalFilename = allocation.finalFilename,
                        finalUri = finalUri,
                        bytesWritten = result.bytesWritten,
                    )
                )
            }
            is ObjectTransferResult.Failure.PartialPayload -> {
                allocation.runCatching { discard() }
                _events.tryEmit(
                    CaptureEvent.Failed(
                        handle = handle,
                        filename = allocation.finalFilename,
                        reason = CaptureEvent.Failed.Reason.PartialPayload(
                            advertised = result.advertisedSizeBytes,
                            written = result.bytesWritten,
                        ),
                    )
                )
            }
            is ObjectTransferResult.Failure.PtpResponseError -> {
                allocation.runCatching { discard() }
                _events.tryEmit(
                    CaptureEvent.Failed(
                        handle = handle,
                        filename = allocation.finalFilename,
                        reason = CaptureEvent.Failed.Reason.PtpError(
                            responseCode = result.responseCode,
                        ),
                    )
                )
            }
            is ObjectTransferResult.Failure.TransportError -> {
                allocation.runCatching { discard() }
                _events.tryEmit(
                    CaptureEvent.Failed(
                        handle = handle,
                        filename = allocation.finalFilename,
                        reason = CaptureEvent.Failed.Reason.Transport(message = result.message),
                    )
                )
            }
        }
    }

    private fun mimeTypeFor(format: Int): String = when (format) {
        PtpIpConstants.FMT_JPEG -> "image/jpeg"
        PtpIpConstants.FMT_CANON_CR2 -> "image/x-canon-cr2"
        PtpIpConstants.FMT_CANON_CR3 -> "image/x-canon-cr3"
        else -> "application/octet-stream"
    }

    companion object {
        /** Buffer slots for UI observers. Dropping after this is acceptable. */
        private const val UI_FLOW_BUFFER = 64
    }
}
