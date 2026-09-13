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
import android.util.Log
import com.RAZStudio.StudioRoom.core.domain.pip.PipStateHolder
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.RawBatchProcessor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Streams freshly-downloaded RAW/JPG files through the v3 RAW pipeline
 * on the fly. Designed to be hooked into [CanonSyncRepository.startBatchDownload]
 * via its `onFinalized` callback.
 *
 * Read-ahead policy
 * ------------------
 * The user asked for exactly one in-flight download ahead of the
 * processor — never more. We model this with a [Channel] of capacity
 * 1 (Channel.RENDEZVOUS would block downloads waiting for processing;
 * capacity 1 lets exactly one file queue up).
 *
 *   download N+1 ─┐
 *                 ├─► [1-slot channel] ─► process N
 *   download N  ──┘            ▲
 *                              └─ when process N done, take N+1
 *
 * If the download side outpaces the processor (rare, processing
 * dominates for raw conversion), `channel.send` suspends — the
 * downloader naturally throttles. If the processor outpaces downloads
 * (the normal case for Wi-Fi PTP/IP on the 6D), processing waits.
 *
 * Lifecycle
 * ---------
 * [start] spawns a processing-worker coroutine that drains the
 * channel. The downloader's `onFinalized` callback is set to
 * [enqueue], which sends each finalized file URI to the channel.
 * [stop] cancels the worker and closes the channel.
 *
 * State
 * -----
 * [state] surfaces a separate progress lane from the download
 * progress — the UI shows BOTH (download running on shot N+1,
 * processor running on shot N).
 */
@Singleton
class DownloadAndProcessCoordinator @Inject constructor(
    private val processor: RawBatchProcessor,
    private val pipState: PipStateHolder,
) {

    sealed interface ProcessPhase {
        data object Idle : ProcessPhase
        data class Running(
            val currentFilename: String,
            val doneCount: Int,
            val failCount: Int,
        ) : ProcessPhase
        data class Done(val doneCount: Int, val failCount: Int) : ProcessPhase
        data class Error(val message: String) : ProcessPhase
    }

    private val _state = MutableStateFlow<ProcessPhase>(ProcessPhase.Idle)
    val state: StateFlow<ProcessPhase> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var workerJob: Job? = null
    // Capacity 1 = one file may queue while another is being processed.
    // Matches the user's "only one next" read-ahead requirement.
    private val channel = Channel<DownloadedFile>(capacity = 1)
    private var context0: RawBatchProcessor.PerFileContext? = null

    private data class DownloadedFile(val uri: Uri, val filename: String)

    /**
     * Stand up the processing worker with a pre-built per-file context
     * (preset, format, dimensions, etc.). Until [stop] is called, every
     * file passed through [enqueue] will be processed in arrival order.
     */
    fun start(ctx: RawBatchProcessor.PerFileContext) {
        if (workerJob?.isActive == true) return
        context0 = ctx
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        var done = 0
        var fail = 0
        workerJob = s.launch {
            try {
                for (file in channel) {
                    _state.value = ProcessPhase.Running(
                        currentFilename = file.filename,
                        doneCount = done,
                        failCount = fail,
                    )
                    pipState.set(
                        PipStateHolder.Snapshot(
                            active = true,
                            title = "Canon Sync",
                            subtitle = "Processing ${file.filename}",
                            progress = null,
                        ),
                    )
                    val result = runCatching {
                        processor.processOneFile(ctx, file.uri)
                    }.getOrElse {
                        Log.w(TAG, "process '${file.filename}' threw", it)
                        null
                    }
                    val ok = result is com.RAZStudio.StudioRoom.feature.photo_editor
                        .raw_v3.RawV3Coordinator.ExportResult.Success
                    if (ok) {
                        done++
                        Log.i(TAG, "process '${file.filename}' OK ($done done, $fail failed)")
                    } else {
                        fail++
                        Log.w(TAG, "process '${file.filename}' FAILED ($done done, $fail failed)")
                    }
                    _state.value = ProcessPhase.Running(
                        currentFilename = "",
                        doneCount = done,
                        failCount = fail,
                    )
                }
                _state.value = ProcessPhase.Done(done, fail)
                pipState.clear()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "processing cancelled at done=$done fail=$fail")
                    _state.value = ProcessPhase.Done(done, fail)
                    throw t
                }
                Log.w(TAG, "processing crashed", t)
                _state.value = ProcessPhase.Error(t.message ?: "Processing failed")
            }
        }
    }

    /**
     * Push a freshly-downloaded file into the processing pipeline.
     * Suspends until the channel has space — when the worker is busy
     * with the previous file AND one file is already queued, this
     * blocks the caller (the downloader), naturally throttling
     * downloads to "one ahead" of processing.
     */
    suspend fun enqueue(uri: Uri, filename: String) {
        if (workerJob?.isActive != true) {
            Log.w(TAG, "enqueue '$filename' while worker idle — ignored")
            return
        }
        channel.send(DownloadedFile(uri, filename))
    }

    /** Tear down. Cancels the worker, closes the channel, resets state. */
    fun stop() {
        workerJob?.cancel()
        workerJob = null
        scope?.cancel()
        scope = null
        // Drain any pending file the downloader pushed but we never
        // got to. They stay on disk — only the processed-output is
        // lost; the originals are still in the user's working folder.
        runCatching { channel.tryReceive() }
        _state.value = ProcessPhase.Idle
        pipState.clear()
    }

    private companion object {
        private const val TAG = "DownloadAndProcess"
    }
}
