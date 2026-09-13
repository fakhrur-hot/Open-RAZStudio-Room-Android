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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.BufferedSource

/**
 * Reads standard PTP/IP `EVENT` packets off the event socket and decodes them
 * into [PtpEvent]s. Runs as a single long-lived coroutine on [Dispatchers.IO].
 *
 * Events are written to [out] via a non-suspending `trySend` so the reader
 * never blocks on a slow consumer. Because [out] is expected to be a
 * `Channel(Channel.UNLIMITED)`, `trySend` succeeds unconditionally — but we
 * defensively log dropped events anyway, in case the upstream channel was
 * misconfigured.
 *
 * Canon EOS shutter events (0xC181, 0xC1A7, …) do **not** arrive here — those
 * are polled by [EosEventPoller] through the command channel. This reader
 * handles 0x4002 ObjectAdded (fallback for older bodies), DeviceInfoChanged,
 * StoreFull, CaptureComplete, and PING/PONG keepalives.
 */
internal class EventReader(
    private val source: BufferedSource,
    private val out: SendChannel<PtpEvent>,
    private val onDropped: (PtpEvent) -> Unit = {},
) {

    /**
     * Launch the reader coroutine in [scope]. Cancel the returned [Job] to stop
     * (or close the underlying socket, which raises an IOException out of the
     * blocking source.read and ends the loop).
     */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val framing = PtpIpPacketReader.readFraming(source)
                val packet = PtpIpPacketReader.readBody(source, framing)
                handle(packet)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("EventReader cancelled")
        } catch (t: Throwable) {
            // Any IO/socket failure on the event channel is a graceful end of
            // session, NOT a fatal app crash. The 6D drops the event TCP
            // channel a few seconds after handshake when no shutter activity
            // has happened — re-throwing here propagated to the global
            // uncaught-handler and killed the app. Higher-level code surfaces
            // connection loss via [CanonSyncRepository.connectionState].
            //
            // SocketTimeoutException is the dominant case (camera idles the
            // event channel between shutter presses). Log at DEBUG so it
            // doesn't drown out real failures in production logcat.
            val msg = "EventReader: event socket closed " +
                "(${t.javaClass.simpleName}: ${t.message}) — ending reader cleanly"
            if (t is java.net.SocketTimeoutException) {
                android.util.Log.d("CanonSync", msg)
            } else {
                android.util.Log.w("CanonSync", msg)
            }
        }
    }

    private fun handle(packet: PtpIpPacket) {
        when (packet) {
            is PtpIpPacket.EventPacket -> emit(StandardEventDecoder.decode(packet))
            PtpIpPacket.Ping -> { /* respond on a write-capable wrapper if needed */ }
            PtpIpPacket.Pong -> { /* nothing to do — keepalive ack */ }
            else -> {
                // Anything else on the event socket is unexpected (the command
                // channel handles responses + data phases). Drop silently.
            }
        }
    }

    private fun emit(event: PtpEvent) {
        if (out.trySend(event).isFailure) onDropped(event)
    }
}
