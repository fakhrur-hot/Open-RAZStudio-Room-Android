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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.media.ExifInterface
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.dao.ThumbnailDao
import com.RAZStudio.StudioRoom.core.database.entity.ThumbnailEntity
import com.RAZStudio.StudioRoom.feature.gallery_workspace.importer.PhotoFormat
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3Engine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scroll-window-driven thumbnail generation queue.
 *
 * The worker is NEVER fed a whole project. Instead the caller describes the
 * currently visible range plus a bounded lookahead on either side via
 * [setWindow]. Moving the window supersedes the previous one, cancelling any
 * pending (not-yet-running) jobs that fell outside the new window. In-flight
 * generation jobs are allowed to complete — cancelling a decode in the middle
 * is more expensive than finishing it, and the semaphore caps the blast radius
 * to two concurrent operations.
 *
 * Queue depth is therefore bounded by:
 *   `visible.count() + 2 * lookahead`
 * …regardless of project size. A 3,000-photo project never inflates the queue.
 *
 * Concurrency is bounded by a [Semaphore] with 2 permits (Req 6.3). The bound
 * covers all projects collectively, not per-project, so two open projects still
 * run at most 2 concurrent generation coroutines.
 *
 * When the Project_Grid is closed, [cancelProject] cancels all pending and
 * in-flight jobs for that project (Req 6.5).
 *
 * Generation itself is stubbed here. Task 6.2 fills in [generateThumbnail].
 *
 * Requirements: 6.3, 6.4, 6.5
 */
@Singleton
class ThumbnailWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thumbnailDao: ThumbnailDao,
    private val photoDao: PhotoDao,
) {

    /**
     * Class-owned coroutine scope. Uses [SupervisorJob] so a failure in one
     * generation coroutine does not propagate to siblings.
     */
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * At most 2 generation coroutines run concurrently across all projects
     * (Req 6.3). This is a hard bound; additional launches block until a
     * permit is released.
     */
    private val semaphore = Semaphore(2)

    /**
     * Per-project state: a list of pending [Job]s in priority order
     * (visible-first, then lookahead), plus any in-flight jobs that have
     * already acquired a semaphore permit and are generating.
     *
     * Access is always confined to the calling thread or via synchronized
     * blocks; callers hold [lock] when reading or mutating this map.
     */
    private val projectJobs = mutableMapOf<Long, ProjectQueue>()
    private val lock = Any()

    /**
     * Photos whose generation failed in this session (Requirement 6.9).
     *
     * Session-scoped rather than persisted: a failure is often environmental (a
     * detached SD card, a revoked grant), so it should not be remembered across
     * restarts and turn a temporary problem into a permanent placeholder.
     */
    private val failedThisSession = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    )

    /** Photos the grid should render a placeholder for, instead of a thumbnail. */
    fun hasFailed(photoId: Long): Boolean = photoId in failedThisSession

    private fun markFailed(photoId: Long) { failedThisSession += photoId }

    /** Clears failure memory so a new grid session retries once more (Req 6.9). */
    fun startGridSession(projectId: Long) {
        failedThisSession.clear()
        Log.d(TAG, "startGridSession($projectId): failure memory cleared")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Replace the active window for [projectId] with [visible] + [lookahead].
     *
     * Cancels all pending jobs that fell outside the new window. Photo IDs
     * already cached (a thumbnail row exists in the DB) are skipped. Visible
     * IDs are enqueued before lookahead IDs (Req 6.4).
     *
     * [visible] is the index-based range of photo IDs currently on screen.
     * [lookahead] is the number of IDs to prefetch beyond each edge of the
     * visible range. The caller is responsible for mapping grid row indices to
     * photo IDs and expanding the window before calling here.
     *
     * This function is NOT a suspend function — the caller should be able to
     * call it on the UI thread from a scroll callback. The internal work
     * (DB check + launch) happens on [scope], which uses Dispatchers.IO.
     *
     * Queue depth invariant: the number of live pending jobs for this project
     * after return is at most `visible.count() + 2 * lookahead`.
     *
     * @param projectId  The project whose window is being updated.
     * @param visible    The photo IDs currently visible on screen, in display order.
     * @param lookahead  How many additional IDs beyond the visible range to enqueue.
     */
    fun setWindow(
        projectId: Long,
        orderedIds: List<Long>,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        lookahead: Int,
    ) {
        scope.launch {
            setWindowInternal(
                projectId, orderedIds, firstVisibleIndex, lastVisibleIndex, lookahead,
            )
        }
    }

    /**
     * Prefetch-priority generation for photos that arrived in [projectId]
     * without a cached thumbnail — the move/copy path of Requirement 8.5b.
     *
     * Fire-and-forget and OUTSIDE the window queue: the batch operation must
     * never block on generation, and a later grid session's [setWindow] for the
     * same ids is harmless (already-cached ids are skipped there). Runs the ids
     * SEQUENTIALLY under one semaphore permit, so a large batch occupies at
     * most one of the two decode slots and visible-window jobs keep a
     * permit's worth of priority.
     */
    fun prefetch(projectId: Long, photoIds: List<Long>) {
        if (photoIds.isEmpty()) return
        scope.launch {
            for (photoId in photoIds) {
                if (thumbnailDao.getByPhotoId(photoId) != null) continue
                semaphore.withPermit {
                    if (isActive) generateThumbnail(photoId)
                }
            }
            Log.d(TAG, "prefetch(project=$projectId): ${photoIds.size} ids done")
        }
    }

    /**
     * Cancel all pending and in-flight thumbnail jobs for [projectId].
     *
     * Called when the Project_Grid for this project closes (Req 6.5). Returns
     * immediately after initiating cancellation; coroutines may still be
     * unwinding when this returns.
     */
    fun cancelProject(projectId: Long) {
        val queue = synchronized(lock) { projectJobs.remove(projectId) } ?: return
        scope.launch {
            queue.cancelAll()
            Log.d(TAG, "cancelProject($projectId): cancelled ${queue.totalCount()} jobs")
        }
    }

    // -------------------------------------------------------------------------
    // Internal implementation
    // -------------------------------------------------------------------------

    /**
     * The actual window-update logic, runs inside [scope] on Dispatchers.IO.
     *
     * 1. Compute the new window: visible IDs first, then lookahead IDs beyond
     *    each edge.
     * 2. Cancel pending jobs whose photo ID fell outside the new window (do NOT
     *    cancel in-flight ones — see class doc).
     * 3. For each ID in the new window that has no cached thumbnail, launch a
     *    generation coroutine gated by [semaphore].
     */
    private suspend fun setWindowInternal(
        projectId: Long,
        orderedIds: List<Long>,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        lookahead: Int,
    ) {
        // The window is sliced by INDEX into the display order, never by
        // arithmetic on photo IDs.
        //
        // Photo IDs are sparse autoincrement keys, not positions: they gap after
        // any deletion, and they carry no ordering at all once the grid sorts by
        // capture date or filename. Deriving a range from them enqueued ids that
        // do not exist — observed on device as a burst of
        // "generateThumbnail(N): photo not found" for every gap.
        if (orderedIds.isEmpty()) return
        val lastIndex = orderedIds.lastIndex
        val firstVis = firstVisibleIndex.coerceIn(0, lastIndex)
        val lastVis = lastVisibleIndex.coerceIn(firstVis, lastIndex)

        val visibleIds = orderedIds.subList(firstVis, lastVis + 1)

        // Lookahead by index, clamped to the list, so it can never name a photo
        // outside the project.
        val beforeFrom = (firstVis - lookahead).coerceAtLeast(0)
        val lookaheadBefore = if (beforeFrom < firstVis) {
            orderedIds.subList(beforeFrom, firstVis).asReversed()
        } else emptyList()

        val afterTo = (lastVis + 1 + lookahead).coerceAtMost(orderedIds.size)
        val lookaheadAfter = if (lastVis + 1 < afterTo) {
            orderedIds.subList(lastVis + 1, afterTo)
        } else emptyList()

        // Visible first, so on-screen tiles are generated before prefetch.
        val windowIds: List<Long> = visibleIds + lookaheadBefore + lookaheadAfter
        val windowSet = windowIds.toHashSet()

        // Acquire lock to swap the queue out atomically.
        val oldQueue: ProjectQueue
        val newQueue: ProjectQueue
        synchronized(lock) {
            oldQueue = projectJobs[projectId] ?: ProjectQueue()
            newQueue = ProjectQueue()
            projectJobs[projectId] = newQueue
        }

        // Cancel ONLY pending jobs that fell outside the new window. In-flight
        // jobs have the semaphore and are progressing — interrupting them costs
        // more than finishing, and two concurrent decodes is the expected steady
        // state.
        oldQueue.cancelPendingOutside(windowSet)

        // Carry forward in-flight jobs (they will release the semaphore when
        // done and update the DB themselves).
        val carried = oldQueue.drainInFlight()
        synchronized(lock) {
            newQueue.addInFlight(carried)
        }

        // For each ID in window order, skip if already cached, then launch.
        for (photoId in windowIds) {
            if (thumbnailDao.getByPhotoId(photoId) != null) continue

            val job = scope.launch {
                semaphore.withPermit {
                    // Move job from pending → in-flight in the queue tracking.
                    synchronized(lock) {
                        projectJobs[projectId]?.markInFlight(photoId)
                    }
                    try {
                        if (isActive) {
                            generateThumbnail(photoId)
                        }
                    } finally {
                        // Remove from in-flight once done (success or failure).
                        synchronized(lock) {
                            projectJobs[projectId]?.removeInFlight(photoId)
                        }
                    }
                }
            }

            synchronized(lock) {
                projectJobs[projectId]?.addPending(photoId, job)
            }
        }

        Log.d(
            TAG,
            "setWindow(project=$projectId, visible=[$firstVis..$lastVis] of " +
                "${orderedIds.size}, lookahead=$lookahead): enqueued ${windowIds.size} ids",
        )
    }

    /**
     * Generate a thumbnail for [photoId].
     *
     * RAW → embedded-preview extraction via [NativeRawDecoder.extractEmbeddedThumbnail].
     * Non-RAW → subsampled decode via [BitmapFactory] with [inSampleSize].
     * Output: JPEG quality 85, long edge ≤ 640 px, written to
     *   `filesDir/gallery/projects/<projectId>/thumbs/<photoId>.jpg`.
     * Upserts a [ThumbnailEntity] row recording the file size and timestamps.
     *
     * No full-resolution decode anywhere in this path (Req 6.1, 6.2).
     *
     * Requirements: 6.1, 6.2, 6.6
     */
    internal suspend fun generateThumbnail(photoId: Long) {
        // Requirement 6.9 — at most one automatic attempt per session for a
        // photo that has already failed.
        if (photoId in failedThisSession) return

        val photo = photoDao.getById(photoId)
        if (photo == null) {
            Log.w(TAG, "generateThumbnail($photoId): photo not found, skipping")
            return
        }

        val uri = runCatching { Uri.parse(photo.sourceUri) }.getOrElse {
            Log.w(TAG, "generateThumbnail($photoId): invalid URI '${photo.sourceUri}'")
            return
        }

        // Branch on the DETECTED FORMAT rather than trying the platform decoder
        // first. BitmapFactory cannot read a proprietary RAW, so leading with it
        // means reading the whole file to learn nothing — and for a 25 MB NEF
        // that is 25 MB of pointless I/O per thumbnail.
        val isRaw = photo.sourceFormat == PhotoFormat.Raw.ordinal
        val bitmap: Bitmap? = runCatching {
            if (isRaw) {
                decodeRawEmbeddedPreview(context, uri, THUMB_LONG_EDGE_PX)
                    ?: decodeSubsampled(context, uri, THUMB_LONG_EDGE_PX)
            } else {
                decodeSubsampled(context, uri, THUMB_LONG_EDGE_PX)
                    ?: decodeRawEmbeddedPreview(context, uri, THUMB_LONG_EDGE_PX)
            }
        }.getOrNull()

        if (bitmap == null) {
            // Requirement 6.9 — record the failure so the grid can show a
            // placeholder and the worker does not retry this photo for the rest
            // of the session. Without this, a permanently undecodable file is
            // retried on every scroll past it.
            markFailed(photoId)
            Log.w(TAG, "generateThumbnail($photoId): all decode paths returned null")
            return
        }

        // Apply EXIF orientation so the thumbnail renders upright.
        val oriented = orientUpright(context, uri, bitmap)

        // Write thumbnail file: filesDir/gallery/projects/<projectId>/thumbs/<photoId>.jpg
        val thumbDir = File(context.filesDir, "gallery/projects/${photo.projectId}/thumbs")
        thumbDir.mkdirs()
        val thumbFile = File(thumbDir, "$photoId.jpg")

        val wrote = runCatching {
            thumbFile.outputStream().use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.isSuccess

        if (!wrote) {
            markFailed(photoId)
            Log.w(TAG, "generateThumbnail($photoId): failed to write ${thumbFile.path}")
            return
        }

        val fileBytes = thumbFile.length()
        val now = System.currentTimeMillis()

        val existing = thumbnailDao.getByPhotoId(photoId)
        if (existing == null) {
            thumbnailDao.insert(
                ThumbnailEntity(
                    photoId = photoId,
                    path = thumbFile.absolutePath,
                    bytes = fileBytes,
                    generatedAt = now,
                    lastAccessedAt = now,
                )
            )
        } else {
            thumbnailDao.update(
                existing.copy(
                    path = thumbFile.absolutePath,
                    bytes = fileBytes,
                    lastAccessedAt = now,
                )
            )
        }

        Log.d(TAG, "generateThumbnail($photoId): written ${fileBytes}B → ${thumbFile.path}")

        enforceCap()
    }

    /**
     * Keep the thumbnail cache under [capBytes] using FAIR-SHARE eviction
     * (Requirement 6.7 / 6.7c).
     *
     * A hard per-project ceiling was rejected: it would penalise the
     * single-large-project user, who is exactly who the cache exists for — one
     * 3,000-photo project would be limited to a fraction of a cache nothing else
     * is competing for. Instead the victim comes from the project furthest ABOVE
     * its fair share (cap ÷ projects holding thumbnails), then LRU within that
     * project. One project may fill the whole cache while it is alone; none can
     * starve another when several compete.
     *
     * The total is read from the summed `bytes` column, never by walking the
     * directory, so eviction cost does not scale with library size (Req 6.7c).
     */
    internal suspend fun enforceCap(capBytes: Long = DEFAULT_CAP_BYTES) {
        val total = runCatching { thumbnailDao.getTotalBytesNow() }.getOrNull() ?: return
        if (total <= capBytes) return

        val projectCount = runCatching { thumbnailDao.countProjectsWithThumbnails() }
            .getOrNull()?.coerceAtLeast(1) ?: 1
        val fairShare = capBytes / projectCount

        val victims = runCatching { thumbnailDao.getLruVictims(fairShare) }
            .getOrElse { emptyList() }

        var freed = 0L
        val needed = total - capBytes
        var evicted = 0
        for (victim in victims) {
            if (freed >= needed) break
            runCatching { File(victim.path).delete() }
            runCatching { thumbnailDao.delete(victim) }
            freed += victim.bytes
            evicted++
        }
        Log.i(
            TAG,
            "enforceCap: total=${total}B cap=${capBytes}B projects=$projectCount " +
                "fairShare=${fairShare}B evicted=$evicted freed=${freed}B",
        )
    }

    /**
     * Mark a thumbnail as just-used so LRU reflects actual viewing, not just
     * generation order. Called by the grid as tiles come into view.
     */
    suspend fun touch(photoId: Long) {
        val row = runCatching { thumbnailDao.getByPhotoId(photoId) }.getOrNull() ?: return
        runCatching {
            thumbnailDao.update(row.copy(lastAccessedAt = System.currentTimeMillis()))
        }
    }

    // -------------------------------------------------------------------------

    private companion object {
        const val TAG = "ThumbnailWorker"
        /** Long-edge cap for generated thumbnails (Req 6.6). */
        const val THUMB_LONG_EDGE_PX = 640
        /** JPEG quality for thumbnail output. */
        const val JPEG_QUALITY = 85

        /**
         * Default cache cap (Requirement 6.7). At 640 px / q85 a thumbnail is
         * roughly 50 KB, so 512 MB holds about 10,000 — the figure that set the
         * 640 px long edge in the first place.
         */
        const val DEFAULT_CAP_BYTES = 512L * 1024 * 1024
    }
}

// =============================================================================
// Queue bookkeeping
// =============================================================================

/**
 * Per-project job tracking.
 *
 * "Pending" = launched but not yet holding a semaphore permit (can be
 *             cancelled cheaply — no decode has started).
 * "In-flight" = holding a permit; a decode is in progress. These are NOT
 *               cancelled by [setWindow] — only by [cancelAll] / [cancelProject].
 *
 * All methods are called while the caller holds [ThumbnailWorker.lock].
 */
private class ProjectQueue {

    /** photoId → Job for work that has not yet acquired the semaphore permit. */
    private val pending = LinkedHashMap<Long, Job>()

    /** photoId → Job for work currently holding the semaphore permit. */
    private val inFlight = LinkedHashMap<Long, Job>()

    fun addPending(photoId: Long, job: Job) {
        pending[photoId] = job
    }

    fun markInFlight(photoId: Long) {
        val job = pending.remove(photoId) ?: return
        inFlight[photoId] = job
    }

    fun removeInFlight(photoId: Long) {
        inFlight.remove(photoId)
    }

    fun addInFlight(jobs: Map<Long, Job>) {
        inFlight.putAll(jobs)
    }

    fun drainInFlight(): Map<Long, Job> {
        val copy = inFlight.toMap()
        inFlight.clear()
        return copy
    }

    /**
     * Cancel pending jobs whose photoId is NOT in [window]. In-flight jobs are
     * left alone — they will finish and update the DB.
     */
    fun cancelPendingOutside(window: Set<Long>) {
        val toCancel = pending.keys.filter { it !in window }
        for (id in toCancel) {
            pending.remove(id)?.cancel()
        }
    }

    /**
     * Cancel ALL jobs — both pending and in-flight. Used by [cancelProject].
     */
    suspend fun cancelAll() {
        val jobs = (pending.values + inFlight.values).toList()
        pending.clear()
        inFlight.clear()
        for (job in jobs) {
            job.cancelAndJoin()
        }
    }

    fun totalCount(): Int = pending.size + inFlight.size
}

// =============================================================================
// Bitmap helpers — copied from WorkspaceSelectorSheet private functions.
// These are file-private to avoid pulling in the Composable presentation layer.
// =============================================================================

/**
 * Largest power-of-two subsample that keeps the long side ≥ [targetPx].
 * Identical to the private `sampleSizeFor` in WorkspaceSelectorSheet.
 */
private fun sampleSizeFor(w: Int, h: Int, targetPx: Int): Int {
    var s = 1
    while (maxOf(w, h) / (s * 2) >= targetPx) s *= 2
    return s
}

/**
 * Subsampled decode of the source image via [BitmapFactory]. Returns null for
 * formats the platform cannot decode (proprietary RAW) — the caller then tries
 * the embedded-preview path.
 *
 * Uses [BitmapFactory.Options.inJustDecodeBounds] to read dimensions without a
 * full decode, then computes a power-of-two [BitmapFactory.Options.inSampleSize]
 * that keeps the long edge ≥ [targetPx]. No full-resolution decode (Req 6.1).
 */
internal fun decodeSubsampled(
    context: android.content.Context,
    uri: android.net.Uri,
    targetPx: Int,
): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
    }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}.getOrNull()

/**
 * Reads the source URI as a byte array and passes it to
 * [NativeRawDecoder.extractEmbeddedThumbnail] to obtain the camera-embedded
 * JPEG preview. Decodes the JPEG preview with a power-of-two subsample so the
 * result fits within [targetPx] on its long edge. No full RAW decode (Req 6.2).
 *
 * Returns null when the file has no embedded JPEG preview, or on any I/O /
 * native failure.
 */
internal fun decodeRawEmbeddedPreview(
    context: android.content.Context,
    uri: android.net.Uri,
    targetPx: Int,
): android.graphics.Bitmap? = runCatching {
    // Extract via the PATH-based native entry point, never by reading the file
    // into a Kotlin ByteArray.
    //
    // `NativeRawDecoder.extractEmbeddedThumbnail(ByteArray)` would allocate the
    // whole RAW on the heap: ~22 MB for the test NEF, 40-80 MB for a 42 MP file,
    // and the concurrency ceiling of 2 doubles it. This app has been killed by
    // lmkd before (see lazy-segmentation-oom-fix), and thumbnailing a 300-photo
    // project is exactly the sustained-pressure case that did it.
    //
    // A `file://` source needs no staging at all. A `content://` source is
    // stream-copied to a small temp file in 64 KB chunks, so peak heap stays
    // flat regardless of file size — trading a transient disk write for an
    // allocation that would otherwise scale with megapixels.
    val localPath = uri.path?.takeIf { uri.scheme == "file" && File(it).canRead() }
    var staged: File? = null
    val jpeg = try {
        val path = localPath ?: run {
            val tmp = File.createTempFile("thumbsrc_", ".raw", context.cacheDir)
            staged = tmp
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, DEFAULT_COPY_BUFFER) }
            } ?: return null
            tmp.absolutePath
        }
        RawV3Engine.extractEmbeddedThumbnail(path)
    } finally {
        staged?.delete()
    }

    if (jpeg == null || jpeg.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
    }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
}.getOrNull()

/** 64 KB — big enough to be efficient, small enough to be invisible in heap. */
private const val DEFAULT_COPY_BUFFER = 64 * 1024

/**
 * Reads the EXIF orientation tag from [uri] and rotates/flips [bmp] to make it
 * upright. Returns [bmp] unchanged on any read failure.
 */
internal fun orientUpright(
    context: android.content.Context,
    uri: android.net.Uri,
    bmp: android.graphics.Bitmap,
): android.graphics.Bitmap = runCatching {
    val orientation = context.contentResolver.openInputStream(uri)?.use { ins ->
        ExifInterface(ins).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    rotateBitmapForExif(bmp, orientation)
}.getOrDefault(bmp)

/**
 * Applies an EXIF orientation value to [src] via a [Matrix] transform.
 * Returns [src] unchanged for [ExifInterface.ORIENTATION_NORMAL] and on error.
 */
private fun rotateBitmapForExif(src: android.graphics.Bitmap, orientation: Int): android.graphics.Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90  -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE  -> { m.postRotate(90f);  m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return src
    }
    return runCatching {
        android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrDefault(src)
}
