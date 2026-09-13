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

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

/**
 * All metadata extracted from a RAW file at Stage A.
 *
 * This is the single source of truth for everything that must survive through the
 * pipeline and reach the edit-exif module for final file writing. Metadata that is
 * NOT encoded in the .cube LUT (aspect ratio, resolution, owner, GPS, etc.) must
 * be preserved here so nothing is lost during color-space conversion.
 *
 * Serialized as JSON alongside each stage cache entry so the pipeline is resumable.
 */
data class RawMetadata(

    // ── File identity ──────────────────────────────────────────────────────────
    val sourceUri: String,
    val fileSha256: String,          // hex SHA-256 of raw bytes — primary cache key
    val fileExtension: String,       // "CR3", "NEF", "ARW", "DNG", …

    // ── Image geometry ────────────────────────────────────────────────────────
    val rawWidth: Int,               // sensor width including masked pixels
    val rawHeight: Int,
    val outputWidth: Int,            // usable crop after LibRaw processing
    val outputHeight: Int,
    val aspectRatioNum: Int = outputWidth,
    val aspectRatioDen: Int = outputHeight,
    val orientation: Int = 0,        // EXIF orientation tag (1-8)

    // ── Color pipeline parameters ──────────────────────────────────────────────
    /** 3×4 camera-to-sRGB matrix from LibRaw imgdata.color.rgb_cam (row-major). */
    val rgbCam: FloatArray,
    val cameraWhiteBalance: FloatArray,  // RGGB multipliers [4]
    val blackLevel: Int = 0,
    val whiteLevel: Int = 65535,
    val colorSpace: Int = 0,         // LibRaw color space index
    val dngVersion: Int = 0,         // 0 = not DNG

    // ── Camera details ────────────────────────────────────────────────────────
    val cameraMake: String = "",
    val cameraModel: String = "",
    val cameraSerial: String = "",
    val softwareVersion: String = "",

    // ── Lens details ─────────────────────────────────────────────────────────
    val lensInfo: String = "",       // e.g. "EF24-70mm f/2.8L II USM"
    val lensMake: String = "",
    val lensId: Int = 0,
    val focalLength: Float = 0f,     // mm
    val focalLength35mm: Float = 0f,
    val aperture: Float = 0f,        // f-number
    val shutterSpeed: Float = 0f,    // seconds
    val iso: Int = 0,
    val exposureBias: Float = 0f,    // EV

    // ── Focus / scene details ─────────────────────────────────────────────────
    val focusDistanceNear: Float = 0f,
    val focusDistanceFar: Float = 0f,
    val flashFired: Boolean = false,
    val lightSource: Int = 0,

    // ── GPS ───────────────────────────────────────────────────────────────────
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAltitude: Double? = null,
    val gpsTimestamp: String = "",

    // ── Ownership / provenance ────────────────────────────────────────────────
    val artist: String = "",
    val copyright: String = "",
    val imageDescription: String = "",
    val userComment: String = "",

    // ── Capture timestamps ────────────────────────────────────────────────────
    val dateTimeOriginal: String = "",
    val dateTimeDigitized: String = "",

    // ── Thumbnail ────────────────────────────────────────────────────────────
    val thumbnailBytes: ByteArray? = null,
) {
    companion object {
        val EMPTY = RawMetadata(
            sourceUri = "",
            fileSha256 = "",
            fileExtension = "",
            rawWidth = 0,
            rawHeight = 0,
            outputWidth = 0,
            outputHeight = 0,
            rgbCam = FloatArray(12),
            cameraWhiteBalance = FloatArray(4) { 1f },
        )
    }
}
