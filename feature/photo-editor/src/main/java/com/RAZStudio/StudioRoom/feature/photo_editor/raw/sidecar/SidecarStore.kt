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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar

import android.content.Context
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * v2-integration §6.1 / §B.3 — XMP sidecar reader/writer.
 *
 * Lifecycle:
 *  • [load] is called from `openRawFile` before any pipeline starts. Returns null when
 *    no sidecar exists or the file is corrupt; the caller treats that as "fresh open".
 *  • [save] is called from the macro-change observer. Debounced 500 ms per source URI so
 *    slider drag does not thrash disk; coalesced into the most recent state.
 *  • [pushRevision] is called when an action is committed (e.g. the user moves between
 *    tools) and snapshots the current state into the history stack.
 *  • [revert] restores a specific revision and writes the result back immediately.
 *
 * Storage location:
 *  1. **Preferred** — `<rawfile>.xmp` next to the source. Used when the source is on a
 *     writable path (the SAF tree or app-private storage).
 *  2. **Fallback** — `cache/sidecars/<sha>.xmp` when the source is read-only (e.g. a
 *     content:// URI shared from another app). Survives only while the app's cache
 *     survives; that's intentional — read-only sources can't carry a persistent sidecar.
 *
 * Size cap: ~256 KB per file. When the serialized snapshot exceeds the cap, oldest
 * revisions are evicted from the tail of the history stack until under the cap. The
 * current macro + workspace are never dropped.
 */
class SidecarStore(
    private val context: Context,
    /**
     * Where sidecars go. Defaulted to [DefaultSidecarResolver] so the two
     * existing call sites — RawEditorComponent and RawPipelineCoordinator —
     * behave exactly as before. Gallery Workspace injects a
     * ProjectSidecarResolver instead, which is how "beside the original when a
     * folder grant exists, per-project fallback otherwise" arrives here without
     * this class learning what a Project is.
     */
    private val resolver: SidecarResolver = DefaultSidecarResolver(context),
) {

    /** Per-URI mutex so concurrent saves for the same file serialise but different files don't. */
    private val mutexes = mutableMapOf<String, Mutex>()
    private val mutexesGuard = Mutex()

    /** Debounce jobs keyed by source URI string. */
    private val pendingWrites = mutableMapOf<String, Job>()

    private val writerScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Read the sidecar for [sourceUri]. Tries the next-to-source location first, then the
     * app-cache fallback. Returns null on missing file, corrupt content, or any I/O error.
     */
    suspend fun load(sourceUri: Uri): SidecarSnapshot? {
        return withMutex(sourceUri) {
            val xmp = readXmp(sourceUri) ?: return@withMutex null
            val snapshot = runCatching { SidecarXmpSerializer.fromXmp(xmp) }.getOrNull()
            if (snapshot == null) {
                Log.w(TAG, "load: sidecar at $sourceUri exists but failed to parse — ignoring")
            }
            snapshot
        }
    }

    /**
     * Debounced save. The first call after a quiet period starts a 500 ms timer; subsequent
     * calls reset it. Whatever state was passed most recently is the one written. Safe to
     * call from the UI thread — the write itself runs on [Dispatchers.IO].
     */
    fun save(sourceUri: Uri, workspace: WorkspaceConfig, macro: UserMacro) {
        // Don't write at all when the user has disabled sidecars for this workspace.
        if (!workspace.sidecarEnabled) return

        val key = sourceUri.toString()
        val existing = pendingWrites[key]
        existing?.cancel()
        pendingWrites[key] = writerScope.launch {
            delay(DEBOUNCE_MS)
            writeImmediate(sourceUri, workspace, macro, pushRevisionFirst = false)
            pendingWrites.remove(key)
        }
    }

    /**
     * Commit a new revision: the current state is captured into the history stack before
     * being saved as the head. Called when the user moves between tools or otherwise marks
     * a meaningful undo boundary.
     */
    suspend fun pushRevision(sourceUri: Uri, workspace: WorkspaceConfig, macro: UserMacro) {
        if (!workspace.sidecarEnabled) return
        // Cancel any pending debounced write — pushRevision is itself a save.
        val key = sourceUri.toString()
        pendingWrites.remove(key)?.cancel()
        writeImmediate(sourceUri, workspace, macro, pushRevisionFirst = true)
    }

    /**
     * Restore a specific revision (by 0-based index into [SidecarSnapshot.revisions], with
     * 0 being the most recent).
     *
     * Requirement 5.3c: a revert is recorded as a NEW revision rather than discarding the
     * revisions that followed the target, so the revert is itself undoable. Concretely: the
     * state being reverted FROM is pushed onto history (same as any other commit), every
     * OTHER previously-recorded revision is kept, and only the target itself leaves the list
     * — it graduates from "a revision" to "the current head".
     */
    suspend fun revert(sourceUri: Uri, revisionIndex: Int): SidecarSnapshot? {
        val current = load(sourceUri) ?: return null
        if (revisionIndex < 0 || revisionIndex >= current.revisions.size) return null
        val target = current.revisions[revisionIndex]
        val keptOthers = current.revisions.filterIndexed { i, _ -> i != revisionIndex }
        val revisions = (listOf(SidecarRevision(current.lastEditedEpochMs, current.macro)) + keptOthers)
            .take(SidecarSnapshot.HISTORY_LIMIT)
        val restored = current.copy(
            macro = target.macro,
            revisions = revisions,
            lastEditedEpochMs = System.currentTimeMillis(),
        )
        writeImmediate(sourceUri, restored.workspace, restored.macro, pushRevisionFirst = false,
            forcedRevisions = revisions)
        return restored
    }

    /**
     * Write a full [SidecarSnapshot] (workspace + macro + revisions + actionStack) exactly
     * as given, through the SAME resolver + mutex + size-cap machinery as every other write
     * here. [hasVisibleEdit] is reported to the resolver via
     * [SidecarResolver.onSnapshotWritten] after a successful write.
     *
     * The v3 action stack is a field [SidecarXmpSerializer] already round-trips but that
     * [save]/[pushRevision]/[revert] never touch (they predate it and operate on a flat
     * macro) — this is the write path for callers that compose the full snapshot themselves.
     */
    suspend fun writeSnapshot(sourceUri: Uri, snapshot: SidecarSnapshot, hasVisibleEdit: Boolean) {
        if (!snapshot.workspace.sidecarEnabled) return
        withMutex(sourceUri) {
            val previous = readXmp(sourceUri)?.let {
                runCatching { SidecarXmpSerializer.fromXmp(it) }.getOrNull()
            }
            // Never replace a persisted action stack with an empty one. The
            // first-open workspace write used to land after the user had
            // already edited, wiping every card on the next reopen.
            var trimmed = if (
                snapshot.actionStack.isEmpty() &&
                previous?.actionStack?.isNotEmpty() == true
            ) {
                snapshot.copy(
                    actionStack = previous.actionStack,
                    macro = previous.macro,
                )
            } else snapshot
            val reportedVisible = hasVisibleEdit || trimmed.actionStack.any { entry ->
                entry.id != com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction.ORIGINAL_ID
            }
            var xmp = SidecarXmpSerializer.toXmp(trimmed)
            while (xmp.length > MAX_SIDECAR_BYTES && trimmed.revisions.isNotEmpty()) {
                trimmed = trimmed.copy(revisions = trimmed.revisions.dropLast(1))
                xmp = SidecarXmpSerializer.toXmp(trimmed)
            }
            val location = resolver.resolve(sourceUri)
            if (writeAt(location, xmp)) {
                resolver.onSnapshotWritten(location, reportedVisible)
            }
        }
    }

    /**
     * Write ONE complete snapshot — workspace + composed macro + the full action
     * stack — on this store's own [writerScope], after cancelling any pending
     * debounced [save] for the same file. Returns the Job so a caller that is
     * about to die (editor exit, export hand-off, component destroy) can rely
     * on the write outliving it.
     *
     * Replaces the two-writer dance for project photos: [save] wrote a
     * macro-only file after 500 ms on this scope, while the action stack was
     * re-attached by a SEPARATE 700 ms job on the editor component's scope.
     * Closing the editor within ~1.2 s of the last slider move cancelled that
     * second job, leaving a sidecar with an EMPTY action stack — and the
     * reopen restore's `actionStack.isEmpty() → return` then dropped every
     * card ("when I reopen the edited photo all editing is gone", 2026-09-07).
     */
    fun flushSnapshotDetached(
        sourceUri: Uri,
        workspace: WorkspaceConfig,
        macro: UserMacro,
        actionStack: List<SidecarActionEntry>,
        hasVisibleEdit: Boolean,
    ): Job {
        cancelPending(sourceUri)
        return writerScope.launch {
            val previous = withMutex(sourceUri) {
                readXmp(sourceUri)?.let { runCatching { SidecarXmpSerializer.fromXmp(it) }.getOrNull() }
            }
            val snapshot = SidecarSnapshot(
                workspace = workspace,
                macro = macro,
                revisions = previous?.revisions ?: emptyList(),
                actionStack = actionStack,
                lastEditedEpochMs = System.currentTimeMillis(),
                appVersion = appVersion(),
            )
            writeSnapshot(sourceUri, snapshot, hasVisibleEdit)
        }
    }

    /** Forget all in-memory pending writes (e.g. on session close). Pending writes lost. */
    fun cancelPending(sourceUri: Uri) {
        pendingWrites.remove(sourceUri.toString())?.cancel()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private suspend fun writeImmediate(
        sourceUri: Uri,
        workspace: WorkspaceConfig,
        macro: UserMacro,
        pushRevisionFirst: Boolean,
        forcedRevisions: List<SidecarRevision>? = null,
    ) {
        withMutex(sourceUri) {
            val now = System.currentTimeMillis()
            val previous = readXmp(sourceUri)?.let { SidecarXmpSerializer.fromXmp(it) }

            val revisions = when {
                forcedRevisions != null -> forcedRevisions
                pushRevisionFirst && previous != null -> {
                    // Prepend the previous head; cap at HISTORY_LIMIT.
                    (listOf(SidecarRevision(previous.lastEditedEpochMs, previous.macro)) +
                        previous.revisions).take(SidecarSnapshot.HISTORY_LIMIT)
                }
                else -> previous?.revisions ?: emptyList()
            }

            var snapshot = SidecarSnapshot(
                workspace = workspace,
                macro = macro,
                revisions = revisions,
                // CARRY the previously-persisted action stack forward. This
                // writer only knows the flat composed macro; the stack is
                // refreshed separately by RawEditorComponent's
                // saveActionStackSidecar (a delayed load-modify-write). Before
                // this line, every save()/pushRevision() CLOBBERED the stack to
                // empty and depended on that delayed job to re-attach it — but
                // save() runs on this store's own writerScope and survives the
                // editor's death, while the re-attach job dies with the
                // component scope. Exiting within ~1 s of the last edit
                // (instant for project photos) therefore stranded the sidecar
                // with macro-only state, and the reopen restore then dropped
                // every card (empty-stack early return) or, downstream, the
                // workspace-default cards — the "reopen looks totally
                // different / darker" bug (2026-08-28).
                actionStack = previous?.actionStack ?: emptyList(),
                lastEditedEpochMs = now,
                appVersion = appVersion(),
            )
            var xmp = SidecarXmpSerializer.toXmp(snapshot)

            // Enforce size cap by dropping oldest revisions until under the limit.
            while (xmp.length > MAX_SIDECAR_BYTES && snapshot.revisions.isNotEmpty()) {
                snapshot = snapshot.copy(revisions = snapshot.revisions.dropLast(1))
                xmp = SidecarXmpSerializer.toXmp(snapshot)
            }

            writeAt(resolver.resolve(sourceUri), xmp)
        }
    }

    private suspend fun readXmp(sourceUri: Uri): String? =
        readAt(resolver.resolve(sourceUri))

    /**
     * Read a sidecar from wherever it lives.
     *
     * The SAF branch is not optional: a sidecar beside a `content://` original is
     * a *document*, so it can only be opened through the ContentResolver. There
     * is no File for it.
     */
    private fun readAt(location: SidecarLocation): String? = when (location) {
        is SidecarLocation.LocalFile ->
            if (location.file.exists()) runCatching { location.file.readText() }.getOrNull()
            else null

        is SidecarLocation.SafDocument -> runCatching {
            context.contentResolver.openInputStream(location.documentUri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()

        SidecarLocation.None -> null
    }

    /** Write a sidecar to wherever it belongs. Returns false on any failure. */
    private fun writeAt(location: SidecarLocation, xmp: String): Boolean = when (location) {
        is SidecarLocation.LocalFile -> runCatching {
            location.file.parentFile?.mkdirs()
            location.file.writeText(xmp)
            true
        }.getOrElse {
            Log.w(TAG, "writeAt: failed to write sidecar to ${location.file}", it)
            false
        }

        is SidecarLocation.SafDocument -> runCatching {
            // "wt" truncates. Without it a shorter snapshot would leave trailing
            // bytes of the previous one and the XMP would not parse.
            context.contentResolver.openOutputStream(location.documentUri, "wt")
                ?.use { it.write(xmp.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrElse {
            Log.w(TAG, "writeAt: failed to write SAF sidecar ${location.documentUri}", it)
            false
        }

        SidecarLocation.None -> {
            Log.w(TAG, "writeAt: no usable sidecar location — skipping write")
            false
        }
    }

    private suspend fun <T> withMutex(sourceUri: Uri, block: suspend () -> T): T {
        val key = sourceUri.toString()
        val mutex = mutexesGuard.withLock { mutexes.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        pkg.versionName ?: ""
    }.getOrDefault("")

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() shr 4) and 0x0F).toString(16))
                append((b.toInt() and 0x0F).toString(16))
            }
        }
    }

    companion object {
        private const val TAG = "SidecarStore"
        private const val DEBOUNCE_MS = 500L
        /** v2-integration §design.md sidecar size cap. */
        private const val MAX_SIDECAR_BYTES = 256 * 1024
    }
}
