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

package com.RAZStudio.StudioRoom.feature.canon_sync.net

import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.PtpEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls [PtpIpConstants.OC_EOS_GET_EVENT] (0x9116) at a fixed cadence and
 * decodes each data-phase blob into typed [PtpEvent]s, emitting them on [out].
 *
 * Canon EOS shutter events (0xC181 EOS_ObjectAddedEx, 0xC1A7 EOS_ObjectAddedEx64)
 * arrive only through this opcode — they're not pushed as standard PTP/IP EVENT
 * packets — so this poller is the **primary capture-detection mechanism** on
 * EOS bodies. Older bodies that emit 0x4002 ObjectAdded on the event socket
 * instead are handled by [EventReader].
 *
 * Poll cadence: we run roughly every [pollIntervalMs] (default 200 ms). Tighter
 * polling destabilises Canon firmware per gphoto2 release notes — earlier
 * versions used 50 ms and had to back off. 200 ms is fast enough that even at
 * 12 fps burst the camera's internal event queue doesn't overflow between
 * polls (each frame produces one 0xC181 record of ~64 bytes).
 */
internal class EosEventPoller(
    private val runGetEvent: suspend () -> EosPollResult,
    private val out: SendChannel<PtpEvent>,
    private val onDropped: (PtpEvent) -> Unit = {},
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    /** Lightweight value-object the poller asks the client for on each tick. */
    internal data class EosPollResult(
        val transactionId: Int,
        val events: List<PtpEvent>,
    )

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                val poll = runGetEvent()
                for (event in poll.events) {
                    if (out.trySend(event).isFailure) onDropped(event)
                }
            } catch (t: Throwable) {
                if (!isActive) return@launch
                // The most likely cause is a transient PTP DeviceBusy or a
                // dropped Wi-Fi link. Pause and retry — if the session is
                // truly dead, the next runGetEvent() will throw again and we
                // back off. Higher-level lifecycle code is responsible for
                // teardown on persistent failure.
                delay(BACKOFF_AFTER_ERROR_MS)
            }
            delay(pollIntervalMs)
        }
    }

    companion object {
        /** Default poll cadence in milliseconds. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 200

        /** Back off this long after a failed GetEvent before retrying. */
        private const val BACKOFF_AFTER_ERROR_MS: Long = 1_000
    }
}
