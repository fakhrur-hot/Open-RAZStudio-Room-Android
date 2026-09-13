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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Per-action brush mask persistence.
 *
 * Mask bitmaps are saved as PNG (alpha channel carries the painted region) under
 * `<filesDir>/raw_masks/<actionId>.png`. The directory is global across photos —
 * each action carries its own id which is unique enough to be a filename. When an
 * action is deleted from a session, its mask file is deleted with it.
 *
 * M10: moved from `cacheDir` to `filesDir` so painted masks survive:
 *   - System cache eviction between editor sessions
 *   - Configuration changes (screen rotation, dark-mode flip) that recreate
 *     the editor Activity / Composable
 *   - Reopening the same RAW after the app process was killed
 * Trade-off: ~2-5 MB per Applied mask card stays on disk until the user
 * clears app data or deletes the corresponding action.
 *
 * The PNG format is the user's painted alpha, NOT the rendered output. The mask
 * is consumed by [MacroProcessor.applyMaskAdjustmentsFloat] / `applyMaskAdjustments`
 * which sample the alpha channel and weight per-pixel local adjustments by it.
 */
internal object RawMaskStorage {

    private fun masksDir(context: Context): File =
        File(context.filesDir, "raw_masks").also { it.mkdirs() }

    /**
     * Legacy cacheDir location (pre-M10). Used once on first call so any
     * masks the user painted before the upgrade migrate to the persistent
     * dir instead of vanishing on cache eviction.
     */
    private fun legacyMasksDir(context: Context): File =
        File(context.cacheDir, "raw_masks")

    private val migrated = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureMigratedFromCache(context: Context) {
        if (!migrated.compareAndSet(false, true)) return
        val src = legacyMasksDir(context)
        if (!src.exists()) return
        val dst = masksDir(context)
        src.listFiles()?.forEach { f ->
            runCatching {
                val target = File(dst, f.name)
                if (!target.exists()) f.copyTo(target, overwrite = false)
                f.delete()
            }
        }
        runCatching { src.delete() }
    }

    fun maskFile(context: Context, actionId: String): File {
        ensureMigratedFromCache(context)
        return File(masksDir(context), "$actionId.png")
    }

    /**
     * Persist [bitmap] (any config — typically `ALPHA_8` or `ARGB_8888` from the
     * brush canvas) as the mask for [actionId]. Returns the absolute path on
     * success, null if writing failed.
     */
    fun save(context: Context, actionId: String, bitmap: Bitmap): String? {
        val file = maskFile(context, actionId)
        return runCatching {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 0, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    /** Load the mask for [actionId], or null if no mask was ever saved. */
    fun load(context: Context, actionId: String): Bitmap? {
        val file = maskFile(context, actionId)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** Convenience: load by absolute path stored on the action. */
    fun loadFromPath(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /** Delete the mask file for [actionId]. Safe to call when no file exists. */
    fun delete(context: Context, actionId: String) {
        runCatching { maskFile(context, actionId).delete() }
    }

    /** Delete all mask files. Used when wiping all actions or clearing cache. */
    fun deleteAll(context: Context) {
        runCatching { masksDir(context).deleteRecursively() }
    }
}
