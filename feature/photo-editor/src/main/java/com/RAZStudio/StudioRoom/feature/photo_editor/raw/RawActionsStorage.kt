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

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawAction
import java.io.File

/**
 * Handles persistence of [RawAction] lists to/from app-private storage and
 * file sharing via [FileProvider].
 *
 * ── Auto-save (per file) ──────────────────────────────────────────────────────
 * Each RAW source file gets its own XML at:
 *   `<filesDir>/raw_actions/actions_<hashOfSourceKey>.xml`
 * The [RawAction.Original] sentinel is stripped on write and not included in reads.
 *
 * ── Export / Import ───────────────────────────────────────────────────────────
 * [exportToShareUri] serializes the actions list to a cache file and returns a
 * FileProvider content URI suitable for an `ACTION_SEND` share intent.
 * [importFromUri] reads any content URI (file picker result) and deserializes it.
 */
internal object RawActionsStorage {

    private fun actionsFile(context: Context, sourceKey: String): File {
        val dir = File(context.filesDir, "raw_actions")
        if (!dir.exists()) dir.mkdirs()
        val hash = sourceKey.hashCode().let { if (it < 0) "n${-it}" else "$it" }
        return File(dir, "actions_$hash.xml")
    }

    /** Persist [actions] to the per-file cache. Skips the Original sentinel. */
    fun save(context: Context, sourceKey: String, actions: List<RawAction>) {
        runCatching {
            actionsFile(context, sourceKey).writeText(RawActionSerializer.serialize(actions))
        }
    }

    /** Load previously saved actions for [sourceKey]. Returns empty list if none. */
    fun load(context: Context, sourceKey: String): List<RawAction> =
        runCatching {
            val file = actionsFile(context, sourceKey)
            if (file.exists()) RawActionSerializer.deserialize(file.readText()) else emptyList()
        }.getOrDefault(emptyList())

    /**
     * Wipe ALL persisted per-photo action stacks. Called when the user
     * leaves the editor via the back button — the design contract is
     * "exit clears in-flight edits everywhere; re-opening any photo
     * starts fresh." Stops the auto-restore behavior the user reported
     * where reopening an old photo replayed its previous action stack.
     */
    fun clearAll(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "raw_actions")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * Serialize [actions] to a cache file and return a shareable [FileProvider] URI.
     * Authority is `<packageName>.fileprovider` as declared in the manifest.
     */
    fun exportToShareUri(context: Context, actions: List<RawAction>): Uri {
        val dir = File(context.cacheDir, "raw_actions_export").also { it.mkdirs() }
        val file = File(dir, "raw_actions.xml")
        file.writeText(RawActionSerializer.serialize(actions))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Parse an XML file from [uri] (file-picker result) into a list of [RawAction]s.
     * Returns null if the file cannot be read or contains no valid actions.
     */
    fun importFromUri(context: Context, uri: Uri): List<RawAction>? =
        runCatching {
            val xml = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return null
            RawActionSerializer.deserialize(xml).takeIf { it.isNotEmpty() }
        }.getOrNull()
}
