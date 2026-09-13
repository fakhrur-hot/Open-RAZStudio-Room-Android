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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.segmentation

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persists and loads [RawSegmentationMasks] to app-private storage, keyed by the RAW
 * file's SHA-256 hash — the same key used by [RawActionsStorage] and [RawStageCache].
 *
 * Layout under `filesDir`:
 * ```
 * raw_segmentation/
 *   <sha256>/
 *     subject.bin   — 320×320 float32 little-endian  (409 600 bytes)
 *     edge.bin      — 320×320 float32 little-endian  (409 600 bytes)
 * ```
 *
 * If either file is absent or has the wrong size the whole entry is treated as a miss
 * and any stale files are deleted so the next run can write a clean pair.
 */
internal object RawSegmentationStorage {

    private const val TAG = "RawSegStorage"
    private const val ROOT_DIR = "raw_segmentation"

    private val MASK_N     = RawSegmentationMasks.MASK_SIZE * RawSegmentationMasks.MASK_SIZE
    private val MASK_BYTES = MASK_N * 4  // 4 bytes per float32

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Load cached masks for [sha256]. Returns null if no valid cache entry exists.
     */
    fun load(context: Context, sha256: String): RawSegmentationMasks? {
        if (sha256.isEmpty()) return null
        return runCatching {
            val dir         = cacheDir(context, sha256)
            val subjectFile = File(dir, "subject.bin")
            val edgeFile    = File(dir, "edge.bin")

            if (!subjectFile.exists() || !edgeFile.exists()) return null

            if (subjectFile.length() != MASK_BYTES.toLong() ||
                edgeFile.length()    != MASK_BYTES.toLong()) {
                Log.w(TAG, "Corrupted entry for ${sha256.take(8)} — evicting")
                subjectFile.delete()
                edgeFile.delete()
                return null
            }

            RawSegmentationMasks(
                subjectMask = readFloats(subjectFile),
                edgeMask    = readFloats(edgeFile),
            ).also { Log.d(TAG, "Cache hit for ${sha256.take(8)}") }
        }.getOrElse { e ->
            Log.e(TAG, "load failed for ${sha256.take(8)}: ${e.message}")
            null
        }
    }

    /**
     * Persist [masks] for [sha256]. Silently ignores write errors so a cache failure
     * never blocks the UI.
     */
    fun save(context: Context, sha256: String, masks: RawSegmentationMasks) {
        if (sha256.isEmpty()) return
        runCatching {
            val dir = cacheDir(context, sha256)
            writeFloats(File(dir, "subject.bin"), masks.subjectMask)
            writeFloats(File(dir, "edge.bin"),    masks.edgeMask)
            Log.d(TAG, "Saved segmentation for ${sha256.take(8)}")
        }.onFailure { e ->
            Log.e(TAG, "save failed for ${sha256.take(8)}: ${e.message}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun cacheDir(context: Context, sha256: String): File =
        File(context.filesDir, "$ROOT_DIR/$sha256").also { it.mkdirs() }

    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val fb    = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(MASK_N) { fb.get() }
    }

    private fun writeFloats(file: File, data: FloatArray) {
        val buf = ByteBuffer.allocate(MASK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) buf.putFloat(v)
        file.writeBytes(buf.array())
    }
}
