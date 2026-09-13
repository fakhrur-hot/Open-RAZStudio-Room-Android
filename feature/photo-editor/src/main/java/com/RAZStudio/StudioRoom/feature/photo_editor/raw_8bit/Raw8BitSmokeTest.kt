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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw_8bit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * One-shot end-to-end smoke entry: takes a RAW file path or content
 * URI, runs the segmented-CLAHE pipeline, writes the resulting 8-bit
 * JPG to `Downloads/RAZStudio8bitSmoke/`. Returns the output file, or
 * null on any failure.
 *
 * Phase 2 only — no UI plumbing, no editor wiring. Call from a debug
 * menu / instrumented test once Phase 3's selector exists, this gets
 * deleted.
 */
internal object Raw8BitSmokeTest {

    private const val TAG = "Raw8Bit.Smoke"
    private const val OUT_DIR = "RAZStudio8bitSmoke"

    @Suppress("DEPRECATION")
    suspend fun run(
        context: Context,
        rawFilePath: String,
        halfSize: Boolean = false,
        jpgQuality: Int = 92,
    ): File? {
        Log.i(TAG, "run: rawFilePath=$rawFilePath halfSize=$halfSize")
        val bitmap = Raw8BitTonemapper(context).use { tonemapper ->
            tonemapper.tonemap(rawFilePath, halfSize = halfSize)
        } ?: return null.also { Log.e(TAG, "run: tonemap returned null") }

        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            OUT_DIR,
        )
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "run: could not create $outDir")
            bitmap.recycle()
            return null
        }
        val srcName = File(rawFilePath).nameWithoutExtension
        val outFile = File(outDir, "${srcName}_8bit_smoke.jpg")
        val ok = saveJpeg(bitmap, outFile, jpgQuality)
        bitmap.recycle()
        return if (ok) outFile.also { Log.i(TAG, "run: wrote $it (${it.length() / 1024} KB)") }
        else null
    }

    /**
     * Variant that accepts a SAF content URI (the picker hands these
     * out). Copies the URI's bytes into the app's cache dir so LibRaw
     * can mmap a real file, then dispatches to [run].
     */
    suspend fun runFromUri(
        context: Context,
        rawUri: Uri,
        halfSize: Boolean = false,
        jpgQuality: Int = 92,
    ): File? {
        val cached = copyUriToCache(context, rawUri)
            ?: return null.also { Log.e(TAG, "runFromUri: could not stage URI to cache") }
        return try {
            run(context, cached.absolutePath, halfSize = halfSize, jpgQuality = jpgQuality)
        } finally {
            runCatching { cached.delete() }
        }
    }

    private fun saveJpeg(bitmap: Bitmap, dest: File, quality: Int): Boolean = runCatching {
        FileOutputStream(dest).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
    }.getOrElse {
        Log.e(TAG, "saveJpeg failed", it)
        false
    }

    private fun copyUriToCache(context: Context, uri: Uri): File? = runCatching {
        val ext = context.contentResolver.getType(uri)
            ?.substringAfterLast('/')?.lowercase()
            ?: "raw"
        val dst = File(context.cacheDir, "smoke_${System.nanoTime()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dst
    }.getOrElse {
        Log.e(TAG, "copyUriToCache failed", it)
        null
    }
}
