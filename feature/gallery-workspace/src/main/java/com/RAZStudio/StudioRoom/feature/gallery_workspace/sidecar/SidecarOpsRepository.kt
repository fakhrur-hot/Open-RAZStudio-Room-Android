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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.sidecar

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.RAZStudio.StudioRoom.core.database.dao.EditDao
import com.RAZStudio.StudioRoom.core.database.dao.PhotoDao
import com.RAZStudio.StudioRoom.core.database.entity.EditEntity
import com.RAZStudio.StudioRoom.core.database.entity.PhotoEntity
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.UserMacro
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.WorkspaceConfig
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarActionEntry
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarRevision
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarSnapshot
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.sidecar.SidecarXmpSerializer
import com.RAZStudio.StudioRoom.feature.photo_editor.raw_v3.RawV3ActionReplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Which edit categories a paste applies (Requirements 14.14–14.16). */
data class PasteCategories(
    val tone: Boolean = true,
    val color: Boolean = true,
    val curves: Boolean = true,
    val lut: Boolean = true,
    val effects: Boolean = true,
    val details: Boolean = true,
    val vignetteGradient: Boolean = true,
    /** Default OFF (Req 14.15) — a lens profile is kit-specific. */
    val lensCorrection: Boolean = false,
)

/** Best-effort EXIF kit identity, for the cross-kit paste warning (Req 14.12a). */
data class KitInfo(val camera: String, val lens: String)

/**
 * Grid-side sidecar operations: copy / paste-with-categories / reset
 * (Requirements 14.12–14.23).
 *
 * Reads and writes the sidecar at the location the `edits` row RECORDS —
 * resolution (grants, sibling creation) already happened when that row was
 * written. A photo with no edit record yet gets its sidecar at the per-project
 * fallback; the beside-original migration tooling picks it up later. The DB
 * stays an index: the XMP file is authoritative (Req 11.5 / 5.6).
 *
 * Categories map to the action stack's `tabIndex` — the same axis the editor
 * files cards under, so a pasted stack reopens in the editor as ordinary cards.
 * Mask cards are NEVER offered or pasted (Req 14.16): hand-painted masks are
 * pixel-registered to their photo. Crop/rotation is not persisted in our
 * sidecar at all, so that spec entry has nothing to offer yet.
 */
@Singleton
class SidecarOpsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoDao: PhotoDao,
    private val editDao: EditDao,
) {

    // ── Copy (Req 14.12) ─────────────────────────────────────────────────────

    /**
     * Snapshot [photoId]'s sidecar into the [EditClipboard], together with the
     * source-kit metadata the paste warnings need. False when the photo has no
     * readable sidecar (nothing to copy).
     */
    suspend fun copyToClipboard(photoId: Long): Boolean = withContext(Dispatchers.IO) {
        val photo = photoDao.getById(photoId) ?: return@withContext false
        val snapshot = loadSnapshot(photoId) ?: return@withContext false
        val kit = probeKit(photo)
        EditClipboard.set(
            EditClipboard.Entry(
                photoId = photoId,
                projectId = photo.projectId,
                displayName = photo.displayName,
                snapshot = snapshot,
                cameraName = kit.camera,
                lensName = kit.lens,
                aspect = aspectOf(photo),
            )
        )
        true
    }

    /** Long-side/short-side pixel ratio — orientation-agnostic. */
    fun aspectOf(photo: PhotoEntity): Float {
        val w = photo.widthPx.coerceAtLeast(1).toFloat()
        val h = photo.heightPx.coerceAtLeast(1).toFloat()
        return maxOf(w, h) / minOf(w, h)
    }

    /**
     * EXIF Make/Model/LensModel, best effort. RAW containers the framework
     * parser cannot read simply return blanks — the paste sheet treats an
     * unknown kit as a reason to warn, not to block.
     */
    suspend fun probeKit(photo: PhotoEntity): KitInfo = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(photo.sourceUri))?.use { s ->
                val exif = ExifInterface(s)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
                // TAG_LENS_MODEL is missing from older framework constants;
                // the attribute name itself is stable EXIF.
                val lens = exif.getAttribute("LensModel").orEmpty().trim()
                val camera = when {
                    model.startsWith(make, ignoreCase = true) -> model
                    else -> listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ")
                }
                KitInfo(camera, lens)
            }
        }.getOrNull() ?: KitInfo("", "")
    }

    // ── Paste (Req 14.14–14.19) ─────────────────────────────────────────────

    /**
     * Apply [entry]'s edits to [targetPhotoId], filtered by [cats]. The result
     * lands as a NEW REVISION on the target (Req 14.19): the target's previous
     * state is pushed onto its history stack, never overwritten silently.
     * Returns false on any failure; the target is untouched then.
     */
    suspend fun paste(
        targetPhotoId: Long,
        entry: EditClipboard.Entry,
        cats: PasteCategories,
    ): Boolean = withContext(Dispatchers.IO) {
        val target = photoDao.getById(targetPhotoId) ?: return@withContext false
        runCatching {
            val existing = loadSnapshot(targetPhotoId)

            val pastedStack = entry.snapshot.actionStack
                .asSequence()
                // Masks are never pasted (Req 14.16) — a painted mask is
                // pixel-registered to its source photo, and even class-derived
                // masks carry per-photo geometry once committed.
                .filter { it.maskPath == null && it.maskClass == null && it.maskClasses.isEmpty() }
                .filter { it.tabIndex != TAB_MASK_LAYERS }
                .filter { inSelectedCategories(it, cats) }
                .map { it.copy(id = java.util.UUID.randomUUID().toString()) }
                .toList()

            // Workspace: keep the target's decode setup; the lens-correction
            // category alone transplants the source's lens profile (Req 14.15).
            val baseWorkspace = existing?.workspace ?: WorkspaceConfig.Default
            val workspace = if (cats.lensCorrection) {
                val src = entry.snapshot.workspace
                baseWorkspace.copy(
                    lensfunDbDir = src.lensfunDbDir,
                    lensfunCameraId = src.lensfunCameraId,
                    lensfunLensId = src.lensfunLensId,
                    lensfunFocalOverrideMm = src.lensfunFocalOverrideMm,
                    lensfunMatchConfidence = src.lensfunMatchConfidence,
                    lensfunAdaptedMode = src.lensfunAdaptedMode,
                )
            } else baseWorkspace

            val now = System.currentTimeMillis()
            val newSnapshot = SidecarSnapshot(
                workspace = workspace,
                macro = composeFromEntries(pastedStack),
                revisions = pushHistory(existing, now),
                actionStack = pastedStack,
                lastEditedEpochMs = now,
            )
            writeSnapshot(target, newSnapshot, neutral = pastedStack.isEmpty())
        }.onFailure {
            Log.w(TAG, "paste onto $targetPhotoId failed: ${it.message}")
        }.isSuccess
    }

    /** True when at least one selected category would carry anything at all. */
    fun pasteWouldCarry(entry: EditClipboard.Entry, cats: PasteCategories): Boolean =
        cats.lensCorrection || entry.snapshot.actionStack.any {
            it.maskPath == null && it.maskClass == null && it.maskClasses.isEmpty() &&
                it.tabIndex != TAB_MASK_LAYERS && inSelectedCategories(it, cats)
        }

    // ── Reset (Req 14.21–14.23, 5.5) ─────────────────────────────────────────

    /**
     * Reset [photoId]'s edits. The previous state is APPENDED to the revision
     * history (Req 14.21 — "history is retained" is literal), the sidecar file
     * is kept (Req 14.22), and the has-edits indicator clears via the edit
     * row's `isNeutral` flag rather than row deletion (Req 14.23). False when
     * there is nothing to reset.
     */
    suspend fun reset(photoId: Long): Boolean = withContext(Dispatchers.IO) {
        val photo = photoDao.getById(photoId) ?: return@withContext false
        val existing = loadSnapshot(photoId) ?: return@withContext false
        runCatching {
            val now = System.currentTimeMillis()
            val neutral = SidecarSnapshot(
                workspace = existing.workspace,   // decode setup survives a reset
                macro = UserMacro(),
                revisions = pushHistory(existing, now),
                actionStack = emptyList(),
                lastEditedEpochMs = now,
            )
            writeSnapshot(photo, neutral, neutral = true)
        }.onFailure {
            Log.w(TAG, "reset of $photoId failed: ${it.message}")
        }.isSuccess
    }

    // ── Camera + lens profile (grid picker, 2026-09-07) ─────────────────────

    /** "camera · lens" from the sidecar's workspace, or null when no profile is set. */
    suspend fun lensProfileLabel(photoId: Long): String? = withContext(Dispatchers.IO) {
        val ws = loadSnapshot(photoId)?.workspace ?: return@withContext null
        val parts = listOf(ws.lensfunCameraId, ws.lensfunLensId).filter { it.isNotBlank() }
        if (parts.isEmpty()) null else parts.joinToString(" · ") +
            (if (ws.lensfunAdaptedMode) " (adapted)" else "")
    }

    /**
     * Rewrite ONLY the decode workspace of [photoId]'s sidecar via [transform],
     * keeping macro, action stack and history intact (a new revision is pushed,
     * like paste/reset). Creates a neutral sidecar for a photo that has none.
     * This is what the grid's profile picker uses — the editor-side bulk apply
     * (`createInitialSidecar`) writes a NEUTRAL snapshot and would wipe edits.
     */
    suspend fun updateWorkspace(
        photoId: Long,
        transform: (WorkspaceConfig) -> WorkspaceConfig,
    ): Boolean = withContext(Dispatchers.IO) {
        val photo = photoDao.getById(photoId) ?: return@withContext false
        runCatching {
            val existing = loadSnapshot(photoId)
            val now = System.currentTimeMillis()
            val base = existing?.workspace ?: WorkspaceConfig.Default
            val snap = SidecarSnapshot(
                workspace = transform(base),
                macro = existing?.macro ?: UserMacro(),
                revisions = pushHistory(existing, now),
                actionStack = existing?.actionStack ?: emptyList(),
                lastEditedEpochMs = now,
            )
            writeSnapshot(photo, snap, neutral = snap.actionStack.isEmpty())
        }.onFailure { Log.w(TAG, "updateWorkspace($photoId) failed: ${it.message}") }.isSuccess
    }

    /**
     * Apply [transform] to every photo in [projectId] whose EXIF camera+lens
     * equals [sourcePhotoId]'s (case-insensitive; unknown kits never match).
     * Edits on those photos are preserved. Returns the number of photos written
     * INCLUDING the source.
     */
    suspend fun applyWorkspaceToMatchingKit(
        projectId: Long,
        sourcePhotoId: Long,
        transform: (WorkspaceConfig) -> WorkspaceConfig,
    ): Int = withContext(Dispatchers.IO) {
        var written = 0
        if (updateWorkspace(sourcePhotoId, transform)) written++
        val source = photoDao.getById(sourcePhotoId) ?: return@withContext written
        val kit = probeKit(source)
        if (kit.camera.isEmpty() && kit.lens.isEmpty()) return@withContext written
        for (p in photoDao.getAllForProject(projectId)) {
            if (p.id == sourcePhotoId) continue
            val k = probeKit(p)
            val same = k.camera.equals(kit.camera, ignoreCase = true) &&
                k.lens.equals(kit.lens, ignoreCase = true)
            if (same && updateWorkspace(p.id, transform)) written++
        }
        written
    }

    // ── Versions (revision history) ─────────────────────────────────────────

    /** Saved revisions of [photoId], newest first (the sidecar stores them so). */
    suspend fun revisionsOf(photoId: Long): List<SidecarRevision> =
        withContext(Dispatchers.IO) { loadSnapshot(photoId)?.revisions.orEmpty() }

    /**
     * Restore revision [index] as the photo's current edit, pushing the state
     * being replaced onto the history first (so restoring is itself undoable).
     *
     * A revision stores a flat macro, not the card stack — the same shape the
     * editor's own revert uses. It is written back as a SINGLE synthetic action
     * card rather than an empty stack, because the editor prefers the stack on
     * open and an empty one would render the photo as unedited.
     */
    suspend fun restoreRevision(photoId: Long, index: Int): Boolean = withContext(Dispatchers.IO) {
        val photo = photoDao.getById(photoId) ?: return@withContext false
        val existing = loadSnapshot(photoId) ?: return@withContext false
        if (index !in existing.revisions.indices) return@withContext false
        runCatching {
            val target = existing.revisions[index]
            val now = System.currentTimeMillis()
            val kept = existing.revisions.filterIndexed { i, _ -> i != index }
            val snap = SidecarSnapshot(
                workspace = existing.workspace,
                macro = target.macro,
                revisions = (listOf(SidecarRevision(now, existing.macro)) + kept)
                    .take(SidecarSnapshot.HISTORY_LIMIT),
                actionStack = listOf(
                    SidecarActionEntry(
                        id = "_restored_" + now,
                        label = "Restored version",
                        tabIndex = 0,
                        macro = target.macro,
                    )
                ),
                lastEditedEpochMs = now,
            )
            writeSnapshot(photo, snap, neutral = false)
        }.onFailure { Log.w(TAG, "restoreRevision($photoId, $index) failed: ${it.message}") }.isSuccess
    }

    // ── Snapshot IO ──────────────────────────────────────────────────────────

    /** Read the sidecar the `edits` row points at; null when absent/unreadable. */
    suspend fun loadSnapshot(photoId: Long): SidecarSnapshot? = withContext(Dispatchers.IO) {
        val edit = editDao.getByPhotoId(photoId) ?: return@withContext null
        val xmp = readRaw(edit.sidecarPath) ?: return@withContext null
        SidecarXmpSerializer.fromXmp(xmp)
    }

    private fun readRaw(path: String): String? = runCatching {
        if (path.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(path))
                ?.bufferedReader()?.use { it.readText() }
        } else {
            File(path).takeIf { it.canRead() }?.readText()
        }
    }.getOrNull()

    /**
     * Write [snapshot] for [photo] and keep the `edits` row in step. Uses the
     * recorded location when one exists; otherwise the per-project fallback
     * (`projects/<id>/sidecars/<photoId>.xmp`) — never cacheDir (Req 4.10).
     */
    private suspend fun writeSnapshot(
        photo: PhotoEntity,
        snapshot: SidecarSnapshot,
        neutral: Boolean,
    ) {
        val xmp = SidecarXmpSerializer.toXmp(snapshot)
        val existing = editDao.getByPhotoId(photo.id)
        val now = System.currentTimeMillis()
        if (existing != null) {
            writeRaw(existing.sidecarPath, xmp)
            editDao.update(
                existing.copy(
                    revisionCount = existing.revisionCount + 1,
                    updatedAt = now,
                    isNeutral = neutral,
                )
            )
        } else {
            val fallback = File(
                File(context.filesDir, "gallery/projects/${photo.projectId}/sidecars"),
                "${photo.id}.xmp",
            )
            fallback.parentFile?.mkdirs()
            fallback.writeText(xmp)
            editDao.insert(
                EditEntity(
                    photoId = photo.id,
                    sidecarPath = fallback.absolutePath,
                    sidecarBesideOriginal = false,
                    revisionCount = 1,
                    updatedAt = now,
                    isNeutral = neutral,
                )
            )
        }
    }

    private fun writeRaw(path: String, xmp: String) {
        if (path.startsWith("content:")) {
            // "wt" truncates — without it a shorter snapshot leaves trailing
            // bytes of the previous one and the XMP no longer parses.
            context.contentResolver.openOutputStream(Uri.parse(path), "wt")
                ?.bufferedWriter()?.use { it.write(xmp) }
                ?: error("no output stream for $path")
        } else {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(xmp)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The target's pre-paste/pre-reset state, pushed onto its history stack. */
    private fun pushHistory(existing: SidecarSnapshot?, now: Long): List<SidecarRevision> {
        if (existing == null) return emptyList()
        return (listOf(SidecarRevision(now, existing.macro)) + existing.revisions)
            .take(SidecarSnapshot.HISTORY_LIMIT)
    }

    /** Fold the pasted entries into the snapshot's composed macro. */
    private fun composeFromEntries(entries: List<SidecarActionEntry>): UserMacro =
        RawV3ActionReplay.composeMacro(
            entries.map {
                RawAction(
                    id = it.id,
                    label = it.label,
                    tabIndex = it.tabIndex,
                    macro = it.macro,
                    isVisible = it.isVisible,
                    isLocked = it.isLocked,
                    isAutoExposure = it.isAutoExposure,
                )
            }
        )

    private fun inSelectedCategories(e: SidecarActionEntry, cats: PasteCategories): Boolean {
        // AE cards are tonal regardless of the tab they were committed from.
        if (e.isAutoExposure) return cats.tone
        return when (e.tabIndex) {
            TAB_TONE_COLOR -> cats.tone
            TAB_COLOR_TOOLS -> cats.color
            TAB_CURVES_LUT -> cats.curves
            TAB_LUT1, TAB_LUT2, TAB_LUT_ADJ -> cats.lut
            TAB_EFFECTS -> cats.effects
            TAB_TEXTURE_GRAIN -> cats.details
            TAB_VIGNETTE_PANE, TAB_MASKS_LOCAL -> cats.vignetteGradient
            else -> cats.tone   // unknown/legacy indices ride with Tone
        }
    }

    private companion object {
        const val TAG = "SidecarOps"

        // Tab constants mirrored from RawAdjustmentPanel (internal to
        // photo-editor's presentation package, so not importable here). The
        // sidecar schema already fixes these values — they are append-only.
        const val TAB_TONE_COLOR = 0
        const val TAB_COLOR_TOOLS = 1
        const val TAB_CURVES_LUT = 2
        const val TAB_MASKS_LOCAL = 3
        const val TAB_EFFECTS = 4
        const val TAB_TEXTURE_GRAIN = 5
        const val TAB_LUT1 = 11
        const val TAB_LUT2 = 12
        const val TAB_LUT_ADJ = 13
        const val TAB_MASK_LAYERS = 14
        const val TAB_VIGNETTE_PANE = 15
    }
}
