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

import com.RAZStudio.StudioRoom.feature.photo_editor.raw.model.RawMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JNI bridge to Lensfun (lensfun-0.3.4 in the project root).
 *
 * Lensfun corrects three physical lens imperfections in Stage B:
 *   1. Geometric distortion (barrel/pincushion) — coordinate remapping
 *   2. Vignetting            — luminance fall-off toward corners
 *   3. Lateral chromatic aberration — per-channel scale mismatch
 *
 * All corrections are applied in linear light (float16 from Stage A) so that no
 * gamma encoding distorts the correction math.
 *
 * Native library: `liblensfix.so` (built from lensfun-0.3.4 JNI wrapper).
 * Database path: extracted to app's filesDir at first run via [ensureDatabase].
 *
 * If no matching lens profile is found, [correct] returns the input unchanged —
 * the pipeline is never blocked by a missing profile.
 */
object LensfunJniBridge {

    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("lensfix"); true }.getOrDefault(false)
    }

    /**
     * Extract the bundled Lensfun XML database from assets into [dbPath] if not
     * already present. Must be called once before any correction.
     *
     * The database resides in assets/lensfun/ — copy lensfun's data/db xml files
     * into src/main/assets/lensfun/ at build time.
     */
    fun ensureDatabase(dbPath: String): Boolean {
        if (!isAvailable) return false
        return runCatching { nativeInitDatabase(dbPath) }.getOrDefault(false)
    }

    /**
     * Apply lens correction in-place to [pixelsFloat16].
     *
     * @param pixelsFloat16 Float16 RGBA buffer (width × height × 8 bytes, row-major).
     * @param width         Image width.
     * @param height        Image height.
     * @param metadata      Camera/lens metadata from Stage A.
     * @param dbPath        Path to the Lensfun XML database directory.
     *
     * Returns a corrected float16 RGBA buffer of the same size, or the input
     * unchanged if no profile is found or the library is unavailable.
     */
    suspend fun correct(
        pixelsFloat16: ByteArray,
        width: Int,
        height: Int,
        metadata: RawMetadata,
        dbPath: String,
    ): ByteArray = withContext(Dispatchers.Default) {
        if (!isAvailable) return@withContext pixelsFloat16
        runCatching {
            nativeCorrect(
                pixels      = pixelsFloat16,
                width       = width,
                height      = height,
                cameraMake  = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                lensInfo    = metadata.lensInfo,
                focalLength = metadata.focalLength,
                aperture    = metadata.aperture,
                dbPath      = dbPath,
            )
        }.getOrDefault(pixelsFloat16)
    }

    // ── Native declarations ───────────────────────────────────────────────────

    @JvmStatic
    private external fun nativeInitDatabase(dbPath: String): Boolean

    /**
     * Performs distortion, vignetting, and lateral CA correction.
     * Returns a new ByteArray with corrected float16 RGBA pixels.
     */
    @JvmStatic
    private external fun nativeCorrect(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cameraMake: String,
        cameraModel: String,
        lensInfo: String,
        focalLength: Float,
        aperture: Float,
        dbPath: String,
    ): ByteArray
}
