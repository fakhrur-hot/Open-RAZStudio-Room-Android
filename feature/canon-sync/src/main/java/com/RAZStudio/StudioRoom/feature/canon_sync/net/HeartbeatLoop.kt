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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic PTP/IP keep-alive that pings the camera with an **active** opcode
 * to defeat the 6D's onboard Wi-Fi chip power-save.
 *
 * **Why not just GetEvent (0x9116)?** Per gphoto2 + camlib notes and our own
 * pcap analysis, `EOS_GetEvent` is a passive poll — the camera holds the
 * connection open and only fires a data phase when an event is queued. With
 * the camera idle (no shutter, no menu navigation) the 6D's NIC interprets
 * the open-but-quiet TCP socket as link idle and triggers its hardware
 * sleep timer after ~10-30 seconds. The radio drops, `rssi=-127`, the
 * pinned NetworkCallback fires `onLost`, and we tear down.
 *
 * **Why EOS_KeepDeviceOn (0x911D)?** Designed by Canon explicitly as a
 * PTP/IP session keepalive (camlib's `ptp_eos_ping`). Replaces an earlier
 * attempt with PCHDDCapacity (0x911A) — the 6D / 5D-III firmware replies
 * `OperationNotSupported` to PCHDDCapacity AND keeps its idle timer
 * ticking, which defeated the whole point. 0x911D is recognized by every
 * EOS body from the 5D-III onward and resets the firmware-side
 * "client-idle" timer that otherwise closes the PTP socket with a TCP
 * FIN at the ~30-second mark. It's:
 *   1. Lightweight — no params, no data phase, ~14-byte response.
 *   2. **Active** — forces the camera firmware to process a request
 *      and emit a reply. The radio sees real TX+RX traffic, resetting
 *      the hardware sleep timer.
 *   3. State-neutral — purely an "I'm still here" ping. Doesn't change
 *      AE mode, doesn't take a picture, doesn't enter live view.
 *
 * **Cadence:** 3 seconds (see [DEFAULT_INTERVAL_MS]). Gives 3+ heartbeats
 * inside the 6D's NIC sleep window. Faster risks PTP_RC_DeviceBusy when
 * it overlaps EosEventPoller or in-flight image downloads; slower lets
 * the radio enter sleep.
 *
 * **Mutex contract:** [pingFn] is expected to acquire the same
 * `commandMutex` that [EosEventPoller] and `runOperation` use. The PTP
 * spec doesn't allow overlapping operations on the command channel and
 * Canon firmware rejects overlap with PTP_RC_DeviceBusy (0x2019).
 */
internal class HeartbeatLoop(
    /** Caller-supplied function that issues PCHDDCapacity (0x911A). */
    private val pingFn: suspend () -> Boolean,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /**
     * Fired ONCE when [MAX_CONSECUTIVE_FAILURES] consecutive `pingFn`
     * calls fail. Signals that the PTP socket is genuinely dead (e.g.
     * `Broken pipe` because the camera reset the TCP, or `EOFException`
     * because the camera sent FIN). The repository uses this to flip
     * `connectionState` to `Failed`, which triggers the auto-reconnect
     * supervisor.
     */
    private val onSessionDeadDetected: () -> Unit = {},
) {

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        Log.i(TAG, "start: KeepDeviceOn heartbeat @ ${intervalMs}ms cadence")
        var consecutiveFailures = 0
        var deadFired = false
        while (isActive) {
            try {
                val ok = pingFn()
                if (ok) {
                    if (consecutiveFailures > 0) {
                        Log.i(TAG, "heartbeat: socket recovered after $consecutiveFailures " +
                            "failed pings")
                    }
                    consecutiveFailures = 0
                    deadFired = false
                } else {
                    consecutiveFailures++
                    Log.w(TAG, "heartbeat: socket failure (#$consecutiveFailures) " +
                        "— camera may have dropped Wi-Fi")
                }
            } catch (t: Throwable) {
                if (!isActive) return@launch
                consecutiveFailures++
                Log.w(TAG, "heartbeat: ping threw (#$consecutiveFailures) — ${t.javaClass.simpleName}: ${t.message}")
            }
            // After N consecutive failures the session is genuinely dead
            // (Broken pipe / EOFException / repeated timeouts). Fire the
            // callback ONCE so the repository can flip connectionState to
            // Failed and trigger auto-reconnect. We keep looping so the
            // repository's teardown gets a clean coroutine cancellation.
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !deadFired) {
                Log.e(TAG, "heartbeat: $MAX_CONSECUTIVE_FAILURES failures — " +
                    "session declared dead, notifying repository for reconnect")
                deadFired = true
                runCatching { onSessionDeadDetected() }
            }
            delay(intervalMs)
        }
        Log.i(TAG, "stop: heartbeat loop cancelled")
    }

    companion object {
        private const val TAG = "HeartbeatLoop"
        // 6D NIC sleep timer fires ~8-12 s after last active TX. 3 s
        // gives 3+ heartbeats inside that window — enough margin to
        // survive one packet loss. EOS Utility runs 4-7 PCHDDCapacity
        // calls per active 10s window per pcap2; this matches.
        const val DEFAULT_INTERVAL_MS: Long = 3_000
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
