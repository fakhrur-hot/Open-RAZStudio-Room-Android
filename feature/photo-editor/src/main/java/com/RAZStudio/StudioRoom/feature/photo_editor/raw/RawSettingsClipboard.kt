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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction

/**
 * Process-wide edit-settings clipboard — the Lightroom "Copy Settings /
 * Paste Settings / Apply from previous" trio.
 *
 *  • [copy] — explicit user Copy from the Actions tab. Held until overwritten.
 *  • [noteSession] — called by the editor component every time it persists an
 *    action stack. Keeps the most recent stack per source photo (bounded), so
 *    "Apply from previous" can hand back the LAST OTHER photo's edits without
 *    any new persistence layer.
 *  • [pasteableFor] — materialises an entry for a target photo: fresh card ids
 *    (so deleting a pasted card can never delete the source card's mask PNG),
 *    and when the target is a DIFFERENT photo the photo-specific `maskPath` is
 *    dropped while the `maskClass` recipe is kept — exactly the preset-export
 *    semantics, so hand-painted masks don't land misaligned on another image
 *    but auto-segmented ones replay by re-running segmentation.
 *
 * In-memory only by design: the durable equivalents already exist (presets,
 * sidecars, auto-save); this is the fast within-session workflow glue.
 */
object RawSettingsClipboard {

    data class Entry(val sourceKey: String?, val actions: List<RawAction>)

    // Snapshot state, not plain vars: the Actions-tab buttons enable/disable off
    // hasCopy/hasPreviousFor DURING COMPOSITION, so a Copy must recompose them.
    private val copied = androidx.compose.runtime.mutableStateOf<Entry?>(null)
    private val sessions =
        androidx.compose.runtime.mutableStateOf<List<Entry>>(emptyList())
    private const val MAX_SESSIONS = 4

    /** True when an explicit Copy has been made and holds at least one card. */
    val hasCopy: Boolean get() = copied.value?.actions?.isNotEmpty() == true

    fun copy(sourceKey: String?, actions: List<RawAction>) {
        copied.value = Entry(sourceKey, sanitize(actions))
    }

    @Synchronized
    fun noteSession(sourceKey: String?, actions: List<RawAction>) {
        if (sourceKey == null) return
        val cards = sanitize(actions)
        // An emptied stack is still noted (moves the photo to the front with no
        // cards) so "previous" never resurrects edits the user just cleared.
        sessions.value =
            (listOf(Entry(sourceKey, cards)) + sessions.value.filter { it.sourceKey != sourceKey })
                .take(MAX_SESSIONS)
    }

    /** The copied cards prepared for [targetKey], or null when nothing usable. */
    fun pasteableFor(targetKey: String?): List<RawAction>? =
        copied.value?.let { materialize(it, targetKey) }

    /**
     * The most recent OTHER photo's cards prepared for [targetKey] — the
     * Lightroom "Apply from previous". Null when this is the only photo edited
     * this session or the previous photo ended with no edits.
     */
    fun previousFor(targetKey: String?): List<RawAction>? =
        sessions.value.firstOrNull { it.sourceKey != targetKey && it.actions.isNotEmpty() }
            ?.let { materialize(it, targetKey) }

    fun hasPreviousFor(targetKey: String?): Boolean =
        sessions.value.any { it.sourceKey != targetKey && it.actions.isNotEmpty() }

    // Drop only the Original sentinel: workspace-default cards (AI Color
    // Enhance, film-profile curves, …) ARE part of the look being copied, and
    // replaceActions() re-adds the sentinel on paste.
    private fun sanitize(actions: List<RawAction>): List<RawAction> =
        actions.filter { it.id != RawAction.ORIGINAL_ID }

    private fun materialize(entry: Entry, targetKey: String?): List<RawAction>? {
        if (entry.actions.isEmpty()) return null
        val samePhoto = entry.sourceKey != null && entry.sourceKey == targetKey
        return entry.actions.map { a ->
            a.copy(
                id = java.util.UUID.randomUUID().toString(),
                // Hand-painted mask PNGs are pixel-registered to their photo:
                // portable only onto the SAME photo. Auto-mask cards keep their
                // class recipe and are re-segmented on the target by replay.
                maskPath = if (samePhoto) a.maskPath else null,
            )
        }
    }
}
