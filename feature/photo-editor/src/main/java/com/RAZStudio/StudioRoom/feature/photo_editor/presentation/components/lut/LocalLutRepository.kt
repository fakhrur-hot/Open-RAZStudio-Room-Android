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

package com.RAZStudio.StudioRoom.feature.photo_editor.presentation.components.lut

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PREFS_NAME = "local_lut_prefs"
private const val KEY_CUSTOM_LUTS = "custom_luts_json"
private const val KEY_FAVORITES = "favorite_lut_keys_json"

/** Supported extensions for user-imported LUT/preset files. */
// cube = native 3D LUT; 3dl = Lustre/Flame/Nuke 3D LUT (→ baked cube);
// xmp/lrtemplate = Adobe presets (→ baked cube);
// png/jpg/jpeg/tif/tiff = HaldCLUT images (→ reconstructed cube via HaldClutConverter).
private val SUPPORTED_LUT_EXTENSIONS =
    // "dng" = a Lightroom preset DNG; the crs: settings ride in its embedded
    // XMP packet (LrPresetConverter.extractXmpPacket). Adobe hands presets out
    // in all three containers and users expect any of them to import.
    // "smcube" = the binary smol-cube the app itself ships and exports; without
    // it a user could not re-import a LUT this app produced. "dng" = a Lightroom
    // preset DNG, whose crs: settings ride in the embedded XMP packet
    // (LrPresetConverter.extractXmpPacket). Adobe hands presets out in .xmp,
    // .lrtemplate AND .dng and users expect any of them to import.
    setOf("cube", "smcube", "3dl", "xmp", "lrtemplate", "dng") +
        HaldClutConverter.SUPPORTED_IMAGE_EXTENSIONS

// Storage location for user-imported .cube files.
//
// New (2026-05-25): shared external Pictures/RAZStudio/luts/. Survives `pm clear`
// / Settings → Clear Data — those wipe filesDir + sharedPreferences but leave
// shared external storage alone. The MANAGE_EXTERNAL_STORAGE permission declared
// in AndroidManifest gives us direct file IO without per-add MediaStore calls.
//
// Legacy location: filesDir/custom_luts. Auto-migrated on first call to
// [getCategories] so existing user LUTs don't disappear after the move.
private const val LEGACY_CUSTOM_LUT_DIR = "custom_luts"
private const val SHARED_LUT_ROOT = "RAZStudio/luts"

/**
 * Reads bundled LUTs from assets/luts/<Category>/<name>.cube and persists
 * User's Custom LUTs in <ExternalStorage>/Pictures/RAZStudio/luts/.
 */
class LocalLutRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Public Pictures/RAZStudio/luts/ folder — survives Clear Data and uninstall
     * (uninstall only wipes app-owned data; shared Pictures is preserved).
     * Creates the directory on demand. If external storage isn't mounted (rare:
     * removable SD card unmounted), falls back to the legacy filesDir location
     * so the picker doesn't crash.
     */
    @get:Suppress("DEPRECATION")
    private val customLutDir: File
        get() {
            // Prefer shared Pictures/RAZStudio/luts ONLY when we actually hold
            // MANAGE_EXTERNAL_STORAGE (Android 11+). Without it, direct file IO
            // into public Pictures silently fails on many OEMs — which made the
            // "Save LUT" button appear to do nothing. Default to app-private
            // filesDir (always writable, no permission) so save always works.
            if (hasAllFilesAccess()) {
                val pictures = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES,
                )
                val target = File(pictures, SHARED_LUT_ROOT)
                runCatching {
                    if (!target.exists()) target.mkdirs()
                    if (target.canWrite()) return target
                }
            }
            return File(context.filesDir, LEGACY_CUSTOM_LUT_DIR).also { it.mkdirs() }
        }

    /** True iff the app holds MANAGE_EXTERNAL_STORAGE (all-files access). */
    private fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()

    /** Legacy app-private location, retained for one-time migration on launch. */
    private val legacyCustomLutDir: File
        get() = File(context.filesDir, LEGACY_CUSTOM_LUT_DIR)

    /**
     * Migrate `.cube` files from the old filesDir/custom_luts location into the
     * new shared Pictures/RAZStudio/luts/ folder. Idempotent: a migrated file is
     * deleted from the legacy folder. Best-effort — failures are silent so a
     * broken external-storage state doesn't lose the legacy files.
     */
    private fun migrateLegacyLutsIfPresent() {
        val legacy = legacyCustomLutDir
        if (!legacy.exists()) return
        val dest = customLutDir
        if (dest == legacy) return  // external unavailable; nothing to migrate
        legacy.listFiles { _, name -> name.endsWith(".cube", ignoreCase = true) }
            ?.forEach { src ->
                val target = uniqueFile(dest, src.name)
                runCatching {
                    src.copyTo(target, overwrite = false)
                    src.delete()
                }
            }
        // Drop the empty legacy directory.
        runCatching { legacy.delete() }
    }

    /**
     * Scan the LUT folder and rebuild the SharedPreferences registry from
     * whatever .cube files are present. Used to recover the picker entries
     * after Clear Data wipes prefs but leaves shared-storage LUT files intact.
     * No-op if the registry already covers every file on disk.
     */
    private fun reconcileRegistryWithFolder() {
        val dir = customLutDir
        val onDisk = dir.listFiles { _, name -> name.endsWith(".cube", ignoreCase = true) }
            ?: return
        val registered = getCustomLutEntries().mapTo(mutableSetOf()) { it.filePath }
        val missing = onDisk.filter { it.absolutePath !in registered }
        if (missing.isEmpty()) return
        val updated = getCustomLutEntries().toMutableList()
        for (file in missing) {
            val name = file.nameWithoutExtension.replace('_', ' ')
            updated.add(LutEntry(name = name, assetPath = null, filePath = file.absolutePath))
        }
        saveCustomEntries(updated)
    }

    /**
     * Returns all categories (bundled + User's Custom).
     * Sorted alphabetically; User's Custom is placed first among non-Favorite categories
     * via the UI layer which also prepends the Favorite category.
     */
    suspend fun getCategories(): List<LutCategory> = withContext(Dispatchers.IO) {
        // Best-effort persistence-recovery before scanning. Both no-ops in the
        // steady state; only do real work when:
        //   • legacy filesDir/custom_luts has LUTs that haven't been moved yet
        //   • SharedPreferences was wiped (Clear Data) but shared-storage .cube
        //     files still exist
        migrateLegacyLutsIfPresent()
        reconcileRegistryWithFolder()
        val bundled = loadBundledCategories()
        val custom = loadCustomCategory()
        // Order: User's Custom → Correction → rest (alphabetical). (The
        // "Aesthetic" featured category was removed entirely 2026-08-29 —
        // assets deleted; a stale favorite/sidecar reference to one of its
        // .cube files simply no-ops like any other missing LUT.)
        val all = (bundled + custom).sortedBy { it.categoryName }
        val userCustom  = all.filter { it.categoryName == USER_CUSTOM_CATEGORY }
        val correction  = all.filter { it.categoryName == CORRECTION_CATEGORY }
        val rest        = all.filter {
            it.categoryName != USER_CUSTOM_CATEGORY &&
                it.categoryName != CORRECTION_CATEGORY
        }
        userCustom + correction + rest
    }

    private fun loadBundledCategories(): List<LutCategory> {
        val assetManager = context.assets
        val rootFolders = runCatching { assetManager.list(ASSETS_LUT_ROOT) }.getOrNull()
            ?: return emptyList()

        return rootFolders
            .filter { it.isNotBlank() && !it.startsWith("_") }
            .mapNotNull { folder ->
                // Accept both ASCII .cube and binary .smcube (smol-cube; ~4.5×
                // smaller, faster to load). Native parseCubeFile sniffs the magic,
                // so either extension resolves to the same 3D LUT.
                val files = runCatching {
                    assetManager.list("$ASSETS_LUT_ROOT/$folder")
                }.getOrNull()?.filter {
                    it.endsWith(".cube", ignoreCase = true) || it.endsWith(".smcube", ignoreCase = true)
                } ?: return@mapNotNull null
                if (files.isEmpty()) return@mapNotNull null
                LutCategory(
                    categoryName = folder,
                    entries = files.sorted().map { filename ->
                        LutEntry(
                            name = filename
                                .removeSuffix(".smcube").removeSuffix(".cube")
                                .replace("_", " "),
                            assetPath = "$ASSETS_LUT_ROOT/$folder/$filename",
                            filePath = null
                        )
                    }
                )
            }
    }

    private fun loadCustomCategory(): List<LutCategory> {
        val customEntries = getCustomLutEntries()
        return if (customEntries.isEmpty()) emptyList()
        else listOf(LutCategory(categoryName = USER_CUSTOM_CATEGORY, entries = customEntries))
    }

    private fun getCustomLutEntries(): List<LutEntry> {
        val json = prefs.getString(KEY_CUSTOM_LUTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = obj.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val file = File(path)
                if (!file.exists()) return@mapNotNull null
                LutEntry(name = name, assetPath = null, filePath = path)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Copies the file at [sourceUri] into internal storage. If it's a .cube file, copies as-is.
     * If it's a .lrtemplate or .xmp preset file, auto-converts to a 33-point cube LUT first.
     * Auto-names from the filename, persists it, and returns the saved [LutEntry] (or null on failure).
     *
     * Unsupported extensions are rejected so the picker cannot silently save non-LUT files.
     */
    suspend fun addUserCustomLut(sourceUri: Uri): LutEntry? = withContext(Dispatchers.IO) {
        android.util.Log.i("LocalLutRepository", "addUserCustomLut: URI=$sourceUri")

        val origFilename = resolveFilename(sourceUri) ?: "custom_${System.currentTimeMillis()}.cube"
        val origExtension = origFilename.substringAfterLast('.', "").lowercase()

        android.util.Log.i("LocalLutRepository", "addUserCustomLut: filename='$origFilename', ext='$origExtension'")

        if (origExtension !in SUPPORTED_LUT_EXTENSIONS) {
            android.util.Log.w("LocalLutRepository", "addUserCustomLut: unsupported extension '$origExtension'")
            return@withContext null
        }

        // .smcube is ALREADY a LUT the native parser reads (SML1 magic) — copy the
        // BYTES through untouched. It must never reach the text path below, which
        // would read it as a String and writeText() it back out corrupted.
        if (origExtension == "smcube") {
            val destFile = uniqueFile(customLutDir, origFilename)
            val copied = runCatching {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    destFile.outputStream().use { out -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (!copied || !destFile.exists() || destFile.length() == 0L) {
                android.util.Log.w("LocalLutRepository", "addUserCustomLut: smcube copy failed")
                return@withContext null
            }
            val displayName = origFilename.removeSuffix(".smcube").replace("_", " ")
            val entry = LutEntry(name = displayName, assetPath = null, filePath = destFile.absolutePath)
            persistCustomEntry(entry)
            android.util.Log.i("LocalLutRepository", "addUserCustomLut: smcube → ${destFile.name} (${destFile.length()} bytes)")
            return@withContext entry
        }

        // HaldCLUT image (PNG/JPEG/TIFF) → reconstruct a native cube via the bridge.
        // (Binary, so it bypasses the text-read path below.)
        if (origExtension in HaldClutConverter.SUPPORTED_IMAGE_EXTENSIONS) {
            val cube = decodeHaldImage(sourceUri, origExtension)?.let { bmp ->
                HaldClutConverter.haldBitmapToCubeString(bmp).also { bmp.recycle() }
            }
            if (cube == null) {
                android.util.Log.w("LocalLutRepository",
                    "addUserCustomLut: '$origFilename' is not a valid HaldCLUT (must be square, side = level³, e.g. 512×512)")
                return@withContext null
            }
            val cubeFilename = origFilename.substringBeforeLast('.') + ".cube"
            val displayName = cubeFilename.removeSuffix(".cube").replace("_", " ")
            val destFile = uniqueFile(customLutDir, cubeFilename)
            runCatching { destFile.writeText(cube) }.onFailure {
                android.util.Log.e("LocalLutRepository", "addUserCustomLut: Hald write failed: ${it.message}", it)
                return@withContext null
            }
            if (!destFile.exists()) return@withContext null
            val entry = LutEntry(name = displayName, assetPath = null, filePath = destFile.absolutePath)
            persistCustomEntry(entry)
            android.util.Log.i("LocalLutRepository", "addUserCustomLut: HaldCLUT → ${destFile.name}")
            return@withContext entry
        }

        // Detect if this is a preset/foreign LUT file that needs conversion to .cube
        val needsConversion = origExtension in listOf("lrtemplate", "xmp", "3dl", "dng")
        android.util.Log.i("LocalLutRepository", "addUserCustomLut: needsConversion=$needsConversion")

        // Read the file content. A preset DNG is BINARY — read bytes and lift the
        // XMP packet out, never bufferedReader (which would mangle it).
        val fileContent = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                if (origExtension == "dng") {
                    val xmp = LrPresetConverter.extractXmpPacket(input.readBytes())
                    android.util.Log.i("LocalLutRepository",
                        "addUserCustomLut: DNG preset, embedded XMP = ${xmp?.length ?: -1} chars")
                    xmp ?: return@use null
                } else {
                    val text = input.bufferedReader().readText()
                    android.util.Log.i("LocalLutRepository", "addUserCustomLut: read ${text.length} bytes")
                    text
                }
            }
        }.onFailure { e ->
            android.util.Log.e("LocalLutRepository", "addUserCustomLut: failed to read file: ${e.message}", e)
        }.getOrNull() ?: return@withContext null.also {
            android.util.Log.e("LocalLutRepository", "addUserCustomLut: fileContent is null")
        }

        // Convert if necessary
        val cubeContent = if (needsConversion) {
            android.util.Log.i("LocalLutRepository", "CONVERT_START: $origFilename ($origExtension, ${fileContent.length} bytes)")
            val converted = when (origExtension) {
                "3dl" -> ThreeDlConverter.convertToCubeString(fileContent)
                else  -> LrPresetConverter.convertToCubeString(fileContent, cubeSize = 33)
            }
            android.util.Log.i("LocalLutRepository", "CONVERT_RESULT: converted=${converted != null}, length=${converted?.length ?: 0}")
            if (converted == null) {
                android.util.Log.w("LocalLutRepository", "CONVERT_FAILED: returned null")
                return@withContext null
            }
            // Log first 500 chars to see the header
            val preview = converted.take(500).replace("\n", "\\n")
            android.util.Log.i("LocalLutRepository", "CONVERT_SUCCESS: ${converted.length} bytes, preview: $preview")
            converted
        } else {
            fileContent
        }

        // Save the (converted or original) cube file
        val cubeFilename = if (needsConversion) {
            origFilename.substringBeforeLast('.') + ".cube"
        } else {
            origFilename
        }
        val displayName = cubeFilename.removeSuffix(".cube").replace("_", " ")
        val destFile = uniqueFile(customLutDir, cubeFilename)

        android.util.Log.i("LocalLutRepository", "addUserCustomLut: saving to ${destFile.absolutePath}")

        runCatching {
            destFile.writeText(cubeContent)
        }.onFailure { e ->
            android.util.Log.e("LocalLutRepository", "addUserCustomLut: failed to write file: ${e.message}", e)
            return@withContext null
        }

        if (!destFile.exists()) {
            android.util.Log.e("LocalLutRepository", "addUserCustomLut: file not created at ${destFile.absolutePath}")
            return@withContext null
        }

        android.util.Log.i("LocalLutRepository", "addUserCustomLut: file saved, size=${destFile.length()} bytes")

        val entry = LutEntry(name = displayName, assetPath = null, filePath = destFile.absolutePath)
        persistCustomEntry(entry)

        if (needsConversion) {
            android.util.Log.i("LocalLutRepository", "Converted $origFilename → ${destFile.name}")
        }

        entry
    }

    /**
     * Write a level-[level] identity HaldCLUT PNG into the shared LUT folder and
     * return its path. The bridge workflow: share this out, apply a style/look in
     * any colour app (Capture One, Lightroom, Photoshop…), export the result, and
     * re-import it here — it round-trips back into a native cube. Default level 8
     * = 512×512 (a 64³ LUT). Returns null on failure.
     */
    suspend fun exportIdentityHald(level: Int = 8, name: String? = null): String? = withContext(Dispatchers.IO) {
        val bmp = HaldClutConverter.generateIdentityHald(level)
        // Sanitise the user-supplied name → safe filename stem; fall back to the
        // level-based default. Dedup (_1, _2, …) is handled by the save paths.
        val stem = name?.trim()
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.removeSuffix(".png")
            ?.takeIf { it.isNotBlank() }
            ?: "identity_hald_level$level"
        val filename = "$stem.png"
        val saved = runCatching { saveHaldToDefaultFolder(bmp, filename) }
            .onFailure { android.util.Log.e("LocalLutRepository", "exportIdentityHald failed: ${it.message}", it) }
            .getOrNull()
        bmp.recycle()
        saved
    }

    /**
     * Save the identity HaldCLUT into the app's configured default save folder,
     * inside a `HaldCLUT` subfolder. Tries, in order:
     *   (A) the user's custom SAF save folder → `HaldCLUT/` child (DocumentFile),
     *   (B) the default `Pictures/RAZStudio/HaldCLUT` via direct file IO,
     *   (C) app-private LUT dir (last-resort fallback so the button never no-ops).
     * Returns a human-readable path for the toast, or null on total failure.
     */
    private fun saveHaldToDefaultFolder(bmp: android.graphics.Bitmap, filename: String): String? {
        fun writePng(out: java.io.OutputStream) =
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)

        // (A) Custom SAF save folder (Settings → default save folder), if set.
        val treeUriStr = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext, LutSaveLocationAccess::class.java,
            ).settingsProvider().settingsState.value.saveFolderUri
        }.getOrNull()
        if (!treeUriStr.isNullOrEmpty()) {
            val safPath = runCatching {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    context, android.net.Uri.parse(treeUriStr),
                )
                if (tree != null && tree.isDirectory) {
                    val sub = tree.findFile("HaldCLUT")?.takeIf { it.isDirectory }
                        ?: tree.createDirectory("HaldCLUT")
                    val name = sub?.let { uniqueDocName(it, filename) }
                    val doc = name?.let { sub.createFile("image/png", it) }
                    if (doc != null) {
                        context.contentResolver.openOutputStream(doc.uri)?.use { writePng(it) }
                        "HaldCLUT/$name (in your save folder)"
                    } else null
                } else null
            }.getOrNull()
            if (safPath != null) return safPath
        }

        // (B) Default folder = Pictures/RAZStudio → Pictures/RAZStudio/HaldCLUT.
        @Suppress("DEPRECATION")
        val pics = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES,
        )
        val haldDir = File(pics, "RAZStudio/HaldCLUT")
        val directPath = runCatching {
            if (!haldDir.exists()) haldDir.mkdirs()
            if (haldDir.isDirectory && haldDir.canWrite()) {
                val dest = uniqueFile(haldDir, filename)
                java.io.FileOutputStream(dest).use { writePng(it) }
                if (dest.exists()) dest.absolutePath else null
            } else null
        }.getOrNull()
        if (directPath != null) return directPath

        // (C) Last-resort fallback so the action never silently no-ops.
        val dest = uniqueFile(customLutDir, filename)
        return runCatching {
            java.io.FileOutputStream(dest).use { writePng(it) }
            if (dest.exists()) dest.absolutePath else null
        }.getOrNull()
    }

    /** Append `_n` to [filename] until it doesn't collide in the SAF [dir]. */
    private fun uniqueDocName(
        dir: androidx.documentfile.provider.DocumentFile,
        filename: String,
    ): String {
        if (dir.findFile(filename) == null) return filename
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var i = 1
        while (dir.findFile("${base}_$i$ext") != null) i++
        return "${base}_$i$ext"
    }

    /**
     * Decode a HaldCLUT source image to an ARGB_8888 bitmap. PNG/JPEG go through
     * BitmapFactory; TIFF (incl. 16-bit) goes through OpenCV — Android can't
     * decode TIFF natively. A 16-bit TIFF is tone-mapped to 8-bit (÷257) before
     * the float cube is built, which is visually lossless for a LUT. Returns null
     * (with a log hint) if OpenCV can't decode it — e.g. an OpenCV build without
     * libtiff, in which case the user should export the Hald as PNG instead.
     */
    private fun decodeHaldImage(sourceUri: Uri, ext: String): android.graphics.Bitmap? = runCatching {
        if (ext != "tif" && ext != "tiff") {
            return@runCatching context.contentResolver.openInputStream(sourceUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        }
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: return@runCatching null
        org.opencv.android.OpenCVLoader.initLocal()
        val mat = org.opencv.imgcodecs.Imgcodecs.imdecode(
            org.opencv.core.MatOfByte(*bytes),
            org.opencv.imgcodecs.Imgcodecs.IMREAD_UNCHANGED,
        )
        if (mat.empty()) {
            android.util.Log.w("LocalLutRepository",
                "TIFF decode unavailable in this OpenCV build (no libtiff) — export the HaldCLUT as PNG instead")
            return@runCatching null
        }
        // Normalise bit depth → 8-bit, then channel order → RGBA for ARGB_8888.
        when (org.opencv.core.CvType.depth(mat.type())) {
            org.opencv.core.CvType.CV_16U -> mat.convertTo(mat, org.opencv.core.CvType.CV_8U, 1.0 / 257.0)
            org.opencv.core.CvType.CV_32F,
            org.opencv.core.CvType.CV_64F -> mat.convertTo(mat, org.opencv.core.CvType.CV_8U, 255.0)
        }
        when (mat.channels()) {
            1 -> org.opencv.imgproc.Imgproc.cvtColor(mat, mat, org.opencv.imgproc.Imgproc.COLOR_GRAY2RGBA)
            3 -> org.opencv.imgproc.Imgproc.cvtColor(mat, mat, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA)
            4 -> org.opencv.imgproc.Imgproc.cvtColor(mat, mat, org.opencv.imgproc.Imgproc.COLOR_BGRA2RGBA)
        }
        val bmp = android.graphics.Bitmap.createBitmap(
            mat.cols(), mat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(mat, bmp)
        mat.release()
        bmp
    }.getOrElse {
        android.util.Log.e("LocalLutRepository", "decodeHaldImage($ext) failed: ${it.message}", it)
        null
    }

    /**
     * Persist an already-resolved `.cube` file (e.g. one the user picked and
     * previewed but didn't auto-save) into the User's Lut category. Copies the
     * file into the persistent shared-storage LUT folder, registers it, and
     * returns the saved [LutEntry] pointing at the new persistent path.
     *
     * If [sourcePath] already lives inside [customLutDir] and is registered,
     * returns the existing entry unchanged (idempotent re-save is a no-op).
     */
    suspend fun saveAsUserLut(sourcePath: String, displayName: String): LutEntry? =
        withContext(Dispatchers.IO) {
            val src = File(sourcePath)
            if (!src.exists()) return@withContext null
            val dir = customLutDir
            // Already persisted + registered? Don't duplicate.
            val already = getCustomLutEntries().firstOrNull { it.filePath == src.absolutePath }
            if (already != null) return@withContext already

            val cleanName = displayName.ifBlank { src.nameWithoutExtension.replace('_', ' ') }
            val filename = (cleanName.replace(' ', '_') + ".cube")
            val dest = uniqueFile(dir, filename)
            runCatching { src.copyTo(dest, overwrite = false) }
                .onFailure { return@withContext null }

            val entry = LutEntry(name = cleanName, assetPath = null, filePath = dest.absolutePath)
            persistCustomEntry(entry)
            entry
        }

    /**
     * Copy a picked `.cube` [sourceUri] into a transient cache file (NOT
     * registered in the User's Lut category) so it can be previewed live
     * before the user decides to save it. Returns (absolutePath, displayName)
     * or null on failure. The file lives in filesDir/lut_pending and is
     * overwritten on the next pick — only [saveAsUserLut] makes it permanent.
     */
    suspend fun stageCubeForPreview(sourceUri: Uri): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val filename = resolveFilename(sourceUri) ?: "picked_${System.currentTimeMillis()}.cube"
            val extension = filename.substringAfterLast('.', "").lowercase()
            val dir = File(context.filesDir, "lut_pending").also { it.mkdirs() }
            android.util.Log.i("LocalLutRepository", "stageCubeForPreview: filename='$filename', ext='$extension'")

            if (extension !in SUPPORTED_LUT_EXTENSIONS) {
                android.util.Log.w("LocalLutRepository", "stageCubeForPreview: unsupported extension '$extension'")
                return@withContext null
            }

            // HaldCLUT image (PNG/JPEG/TIFF) → reconstruct a native cube via the bridge.
            if (extension in HaldClutConverter.SUPPORTED_IMAGE_EXTENSIONS) {
                val cube = decodeHaldImage(sourceUri, extension)?.let { bmp ->
                    HaldClutConverter.haldBitmapToCubeString(bmp).also { bmp.recycle() }
                }
                if (cube == null) {
                    android.util.Log.w("LocalLutRepository",
                        "stageCubeForPreview: '$filename' is not a valid HaldCLUT (square, side = level³)")
                    return@withContext null
                }
                val cubeName = filename.substringBeforeLast('.') + ".cube"
                val displayName = cubeName.removeSuffix(".cube").replace("_", " ")
                val dest = File(dir, cubeName)
                runCatching { dest.writeText(cube) }
                    .onFailure { android.util.Log.e("LocalLutRepository", "stageCubeForPreview: Hald write failed: ${it.message}", it); return@withContext null }
                if (!dest.exists()) return@withContext null
                android.util.Log.i("LocalLutRepository", "stageCubeForPreview: HaldCLUT → ${dest.name} (${dest.length()} bytes)")
                return@withContext dest.absolutePath to displayName
            }

            // .3dl (Lustre/Flame/Nuke 3D LUT) → bake to a native .cube before staging.
            if (extension == "3dl") {
                val text = runCatching {
                    context.contentResolver.openInputStream(sourceUri)?.use { it.bufferedReader().readText() }
                }.onFailure { android.util.Log.e("LocalLutRepository", "stageCubeForPreview: .3dl read failed: ${it.message}", it) }
                    .getOrNull() ?: return@withContext null
                val cube = ThreeDlConverter.convertToCubeString(text)
                if (cube == null) {
                    android.util.Log.w("LocalLutRepository", "stageCubeForPreview: '$filename' is not a valid .3dl (need N³ integer triplets)")
                    return@withContext null
                }
                val cubeName = filename.substringBeforeLast('.') + ".cube"
                val displayName = cubeName.removeSuffix(".cube").replace("_", " ")
                val dest = File(dir, cubeName)
                runCatching { dest.writeText(cube) }
                    .onFailure { android.util.Log.e("LocalLutRepository", "stageCubeForPreview: .3dl write failed: ${it.message}", it); return@withContext null }
                if (!dest.exists()) return@withContext null
                android.util.Log.i("LocalLutRepository", "stageCubeForPreview: .3dl → ${dest.name} (${dest.length()} bytes)")
                return@withContext dest.absolutePath to displayName
            }

            // Lightroom preset files (.lrtemplate Lua / .xmp XML) are not cube
            // LUTs — convert them to a 33-point cube before staging so the
            // pipeline (and the user) only ever sees a real .cube file.
            if (extension == "lrtemplate" || extension == "xmp" || extension == "dng") {
                val presetText = runCatching {
                    context.contentResolver.openInputStream(sourceUri)?.use {
                        if (extension == "dng") LrPresetConverter.extractXmpPacket(it.readBytes())
                        else it.bufferedReader().readText()
                    }
                }.onFailure { android.util.Log.e("LocalLutRepository", "stageCubeForPreview: read failed: ${it.message}", it) }
                    .getOrNull() ?: return@withContext null

                android.util.Log.i("LocalLutRepository", "stageCubeForPreview: converting preset (${presetText.length} bytes)")
                val cube = LrPresetConverter.convertToCubeString(presetText, cubeSize = 33)
                if (cube == null) {
                    android.util.Log.w("LocalLutRepository", "stageCubeForPreview: preset conversion returned null")
                    return@withContext null
                }
                val cubeName = filename.substringBeforeLast('.') + ".cube"
                val displayName = cubeName.removeSuffix(".cube").replace("_", " ")
                val dest = File(dir, cubeName)
                runCatching { dest.writeText(cube) }
                    .onFailure { android.util.Log.e("LocalLutRepository", "stageCubeForPreview: write failed: ${it.message}", it); return@withContext null }
                if (!dest.exists()) return@withContext null
                android.util.Log.i("LocalLutRepository", "stageCubeForPreview: converted → ${dest.name} (${dest.length()} bytes)")
                return@withContext dest.absolutePath to displayName
            }

            // .cube AND .smcube both land here and are copied byte-for-byte;
            // RawV3LutStore.parseCubeFile picks the right reader by content.
            val displayName = filename.removeSuffix(".cube").removeSuffix(".smcube").replace("_", " ")
            val dest = File(dir, filename)
            runCatching {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { return@withContext null }
            if (!dest.exists()) return@withContext null
            dest.absolutePath to displayName
        }

    // ── Favorites persistence ─────────────────────────────────────────────────

    /**
     * Returns the ordered list of favorited LUT keys ("CategoryName/entryName").
     * Order reflects the sequence in which they were added (oldest first).
     */
    fun getFavoriteKeys(): List<String> {
        val json = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Adds [key] to favorites (no-op if already present or if limit reached).
     * Returns true if added, false if the list is full or key already exists.
     */
    fun addFavoriteKey(key: String): Boolean {
        val current = getFavoriteKeys().toMutableList()
        if (current.contains(key) || current.size >= MAX_FAVORITES) return false
        current.add(key)
        saveFavoriteKeys(current)
        return true
    }

    /** Removes [key] from favorites. No-op if not present. */
    fun removeFavoriteKey(key: String) {
        val current = getFavoriteKeys().toMutableList()
        if (current.remove(key)) saveFavoriteKeys(current)
    }

    private fun saveFavoriteKeys(keys: List<String>) {
        val array = JSONArray()
        keys.forEach { array.put(it) }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    // ── User's Custom management ──────────────────────────────────────────────

    /** Deletes the User's Custom LUT entry identified by [filePath]. */
    fun removeUserCustomLut(filePath: String) {
        File(filePath).delete()
        com.RAZStudio.StudioRoom.feature.photo_editor.presentation.lut_creator
            .LutOpticalSidecar.deleteBesideCube(filePath)
        val current = getCustomLutEntries().filter { it.filePath != filePath }
        saveCustomEntries(current)
    }

    /**
     * Copies a bundled asset LUT to the app cache dir and returns the cached [File] path
     * so it can be opened as a normal file by the pipeline.
     */
    suspend fun resolveAssetToCache(assetPath: String): String? = withContext(Dispatchers.IO) {
        // Use filesDir (not cacheDir) — Android may clear cacheDir silently, causing
        // CubeLutFilter to fail with ENOENT on files that existed at selection time.
        // Preserve assetPath directory hierarchy so category names (notably Black & White)
        // are preserved in the cached file path for isBlackAndWhiteLutPath / lutBwForce.
        val cacheFile = File(context.filesDir, "lut_cache/$assetPath")
        cacheFile.parentFile?.mkdirs()
        if (cacheFile.exists()) return@withContext cacheFile.absolutePath
        runCatching {
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }.fold(
            onSuccess = { cacheFile.absolutePath },
            onFailure = { null }
        )
    }

    private fun persistCustomEntry(entry: LutEntry) {
        val current = getCustomLutEntries().toMutableList()
        current.add(entry)
        saveCustomEntries(current)
    }

    private fun saveCustomEntries(entries: List<LutEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject()
            obj.put("name", e.name)
            obj.put("path", e.filePath)
            array.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_LUTS, array.toString()).apply()
    }

    private fun resolveFilename(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueFile(dir: File, filename: String): File {
        var f = File(dir, filename)
        var counter = 1
        while (f.exists()) {
            val base = filename.substringBeforeLast('.')
            val ext = filename.substringAfterLast('.', "")
            f = File(dir, "${base}_$counter.$ext")
            counter++
        }
        return f
    }
}

/**
 * Hilt accessor so [LocalLutRepository] (not Hilt-injected — it's `remember`ed
 * with a bare Context) can read the app's configured default save folder for
 * the HaldCLUT export. Mirrors the pattern in RawV3HiltAccess.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface LutSaveLocationAccess {
    fun settingsProvider(): com.RAZStudio.StudioRoom.core.settings.domain.SettingsProvider
}

@Composable
fun rememberLocalLutRepository(): LocalLutRepository {
    val context = LocalContext.current
    return remember(context) { LocalLutRepository(context) }
}

@Composable
fun rememberLutCategories(repository: LocalLutRepository): State<List<LutCategory>> {
    val state = remember { mutableStateOf<List<LutCategory>>(emptyList()) }
    LaunchedEffect(repository) {
        state.value = repository.getCategories()
    }
    return state
}
