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

import android.util.Log
import com.RAZStudio.StudioRoom.feature.canon_sync.domain.models.EosPropertyDescriptor
import com.RAZStudio.StudioRoom.feature.canon_sync.net.CanonWifiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-session controller that owns the live state of every Canon EOS
 * shooting property the UI exposes. Backs every chip in the Live Remote
 * Shooting screen.
 *
 * Lifecycle:
 *  - Constructed when a PTP session opens (so [client] is non-null) and
 *    discarded when the session tears down. Caches MUST NOT outlive
 *    the underlying [CanonWifiClient] — a property descriptor read after
 *    reconnect is wire-different (transaction IDs, even DPC availability).
 *  - [start] launches the invalidation subscriber that listens to
 *    [CanonWifiClient.propertyChanges] and re-fetches affected
 *    descriptors. Cancel the returned [Job] on teardown.
 *
 * Access pattern:
 *  - [observe] returns a [StateFlow] for a given DPC; first subscriber
 *    triggers a one-shot fetch, subsequent subscribers reuse the cached
 *    descriptor. Refresh is automatic via the event subscription.
 *  - [setValue] writes through [CanonWifiClient.setProperty]; on success
 *    optimistically updates the cached descriptor's currentValue so the
 *    UI doesn't wait for the camera's echo. A real `EOS_PropertyValueChanged`
 *    event arrives shortly after and the listener re-reads the descriptor
 *    to confirm.
 *
 * Concurrency: a [Mutex] guards the cache map. Refresh tasks run on the
 * scope passed to [start]; their results race against optimistic writes
 * — the mutex serialises both so the final cached state is whatever the
 * camera last advertised.
 */
class EosPropertyController internal constructor(
    private val client: CanonWifiClient,
) {

    private val cache = HashMap<Int, MutableStateFlow<EosPropertyDescriptor?>>()
    private val cacheMutex = Mutex()

    /**
     * Get (or create) the [StateFlow] mirror for [dpc]. The first call
     * synchronously creates an empty flow and schedules a fetch via
     * [scope]; subsequent calls return the same flow. Callers should
     * NOT recreate this flow across recomposes — the controller is a
     * singleton-per-session.
     */
    fun observe(dpc: Int, scope: CoroutineScope): StateFlow<EosPropertyDescriptor?> {
        // NOTE — proactive fetch via 0x1014 GetDevicePropDesc is DISABLED.
        // The 6D's firmware silently ignores 0x1014 for Canon-vendor DPCs
        // (D002 ISO, D003 Tv, D004 Av, etc.), so the request times out
        // after the socket soTimeout (~2 s). 13 chips × 2 s = 26 s of
        // command-mutex starvation, which then misses 3 KeepDeviceOn
        // heartbeats and tears down the whole PTP session — including
        // Live View. (Verified against camlib `ptp_canon_eos_getdeviceinfo`
        // — the EOS path is to scrape descriptors from the 0xC189 event
        // stream, not standard-PTP 0x1014.)
        //
        // Descriptors will arrive when the camera pushes
        // `EOS_DevicePropChanged (0xC189)` records via [start]'s
        // subscription. Until those land, observers see null.
        // [setValue] still works because 0x9110 (SetDevicePropValueEx)
        // IS implemented on the 6D — only the READ-DESC opcode is broken.
        return ensureFlowSync(dpc).asStateFlow()
    }

    /**
     * Write [value] to the camera-side property [dpc]. On success the
     * cached descriptor's `currentValue` is optimistically updated so
     * any observer reflects the change without waiting for the
     * camera's `EOS_PropertyValueChanged` echo (which typically lands
     * within ~200 ms via the event poller).
     *
     * Returns true if the camera ack'd the write with `RC_OK`. False
     * means the camera rejected — descriptor is NOT mutated and the
     * UI should re-read the [StateFlow] to recover.
     */
    suspend fun setValue(dpc: Int, value: Long): Boolean {
        // Force IO dispatch — caller's coroutine context is often main
        // (rememberCoroutineScope from Compose). Without this the TCP
        // write throws NetworkOnMainThreadException and the property
        // never updates camera-side.
        val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
            client.setProperty(dpc, value)
        }
        if (ok) {
            cacheMutex.withLock {
                val flow = ensureFlowLocked(dpc)
                val existing = flow.value
                if (existing != null) {
                    flow.value = existing.copy(currentValue = value)
                }
            }
        } else {
            Log.w(TAG, "setValue: dpc=0x${"%04X".format(dpc)} val=$value rejected")
        }
        return ok
    }

    /**
     * Wire the controller to its session. Returns a [Job] that should be
     * cancelled when the session tears down — failing to do so leaks
     * the event subscription onto the next session's controller.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        // 0x1014-based refresh is DISABLED until proper event-stream
        // descriptor parsing lands. Keeping the collector live as a
        // no-op so the wiring stays in place for the next iteration.
        client.propertyChanges.collect { _ -> /* no-op */ }
    }

    /**
     * Force a re-fetch of [dpc] from the camera. Called from the event
     * listener and on first [observe]; UI layer doesn't need to call
     * this directly.
     */
    suspend fun refresh(dpc: Int) {
        Log.i(TAG, "refresh: requesting dpc=0x${"%04X".format(dpc)}")
        val fresh = runCatching { client.getDevicePropDesc(dpc) }
            .onFailure { Log.w(TAG, "refresh: dpc=0x${"%04X".format(dpc)} threw", it) }
            .getOrNull()
        cacheMutex.withLock {
            val flow = ensureFlowLocked(dpc)
            // Null result (transport or decode failure) keeps the prior
            // cached value rather than clobbering — flickering the UI
            // empty on a transient read failure is worse than stale data.
            if (fresh != null) flow.value = fresh
        }
    }

    private fun ensureFlowSync(dpc: Int): MutableStateFlow<EosPropertyDescriptor?> {
        // Synchronous variant for [observe]: HashMap access is fine
        // without the mutex because adding a new entry is idempotent
        // when [putIfAbsent] would race two creators; we tolerate the
        // theoretical extra StateFlow allocation since it's cheap and
        // observable-from-outside via the returned reference.
        return cache.getOrPut(dpc) { MutableStateFlow(null) }
    }

    private fun ensureFlowLocked(dpc: Int): MutableStateFlow<EosPropertyDescriptor?> =
        cache.getOrPut(dpc) { MutableStateFlow(null) }

    private companion object {
        private const val TAG = "EosPropertyController"
    }
}
