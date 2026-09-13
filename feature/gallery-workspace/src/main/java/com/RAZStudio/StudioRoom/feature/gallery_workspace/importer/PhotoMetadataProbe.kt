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

package com.RAZStudio.StudioRoom.feature.gallery_workspace.importer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Formats the library recognises. Its ORDINAL is persisted in
 * `PhotoEntity.sourceFormat`, so **entries may only be appended** — reordering
 * or removing one silently reinterprets every existing row.
 *
 * Deliberately a local copy: the equivalent enum in
 * `WorkspaceSelectorSheet.kt` is `private`, so it cannot be reused even though
 * this module does depend on feature/photo-editor.
 */
enum class PhotoFormat {
    Unknown, Jpeg, Webp, Bmp, Png, Tiff, Raw,
}

/** Everything the library indexes about a photo, read WITHOUT decoding it. */
data class PhotoMetadata(
    val displayName: String,
    val mimeType: String,
    val format: PhotoFormat,
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
    val capturedAt: Long?,
    // ── Schema v3: shot metadata (search / filter / date headers / info panel) ──
    val cameraMake: String = "",
    val cameraModel: String = "",
    val lensModel: String = "",
    val iso: Int = 0,
    val apertureF: Float = 0f,
    /** Exposure time in SECONDS. */
    val shutterSpeed: Float = 0f,
    val focalLengthMm: Float = 0f,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
)

/**
 * Reads a photo's indexable metadata (Requirements 3.4, 3.8).
 *
 * **Never decodes a full frame.** Dimensions come from
 * `BitmapFactory.Options(inJustDecodeBounds = true)`, which reads only enough
 * header to report a size, and capture time from EXIF. That constraint is not an
 * optimisation — see the project's lmkd history; a 300-photo import of 42 MP
 * RAWs that decoded frames would be killed.
 */
object PhotoMetadataProbe {

    private const val TAG = "PhotoMetadataProbe"

    fun probe(resolver: ContentResolver, uri: Uri): PhotoMetadata {
        val (name, size) = queryNameAndSize(resolver, uri)
        val mime = resolver.getType(uri).orEmpty()
        val format = formatOf(name, mime)

        // Bounds-only decode. Returns 0/0 for RAW, which BitmapFactory cannot
        // parse at all — EXIF below is the fallback for those.
        var width = 0
        var height = 0
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                width = opts.outWidth.coerceAtLeast(0)
                height = opts.outHeight.coerceAtLeast(0)
            }
        }.onFailure { Log.d(TAG, "bounds decode failed for $name: ${it.message}") }

        var capturedAt: Long? = null
        var shot = ShotMetadata()
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                capturedAt = parseExifDate(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                )
                if (width == 0 || height == 0) {
                    // RAW path: BitmapFactory gave nothing, so trust EXIF.
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                }
                shot = readShot(exif)
            }
        }.onFailure { Log.d(TAG, "exif read failed for $name: ${it.message}") }

        return PhotoMetadata(
            displayName = name,
            mimeType = mime,
            format = format,
            sizeBytes = size,
            widthPx = width,
            heightPx = height,
            capturedAt = capturedAt,
            cameraMake = shot.make,
            cameraModel = shot.model,
            lensModel = shot.lens,
            iso = shot.iso,
            apertureF = shot.aperture,
            shutterSpeed = shot.shutter,
            focalLengthMm = shot.focal,
            gpsLat = shot.lat,
            gpsLon = shot.lon,
        )
    }

    /** Shot fields lifted off one already-open [ExifInterface]. */
    internal data class ShotMetadata(
        val make: String = "", val model: String = "", val lens: String = "",
        val iso: Int = 0, val aperture: Float = 0f, val shutter: Float = 0f,
        val focal: Float = 0f, val lat: Double? = null, val lon: Double? = null,
    )

    /**
     * Read camera/lens/exposure/GPS. The model is stripped of a leading maker
     * ("NIKON CORPORATION" + "NIKON Z 6" → "Z 6") so the filter chips read like
     * the camera's own badge instead of repeating the brand twice. Rational
     * "1/250" style values are resolved by [ratio]; ExifInterface returns them
     * verbatim for some makers.
     */
    internal fun readShot(exif: ExifInterface): ShotMetadata {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
        val rawModel = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
        val makeFirstWord = make.substringBefore(' ').trim()
        val model = if (makeFirstWord.isNotEmpty() &&
            rawModel.startsWith(makeFirstWord, ignoreCase = true)
        ) rawModel.drop(makeFirstWord.length).trim().ifBlank { rawModel } else rawModel
        val lens = (exif.getAttribute("LensModel")
            ?: exif.getAttribute("LensSpecification")).orEmpty().trim()
        val iso = exif.getAttributeInt("PhotographicSensitivity", 0)
            .takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
        val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).toFloat()
        val shutter = ratio(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        val focal = ratio(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
        // android.media.ExifInterface fills a FloatArray and returns false when
        // the file carries no GPS block.
        val latLon = FloatArray(2).takeIf { runCatching { exif.getLatLong(it) }.getOrDefault(false) }
        return ShotMetadata(
            make = make.take(64), model = model.take(64), lens = lens.take(96),
            iso = iso.coerceAtLeast(0), aperture = aperture.coerceAtLeast(0f),
            shutter = shutter, focal = focal,
            lat = latLon?.get(0)?.toDouble(), lon = latLon?.get(1)?.toDouble(),
        )
    }

    /** "1/250" or "0.004" → 0.004f; null/garbage → 0f. */
    private fun ratio(value: String?): Float {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return 0f
        val parts = v.split('/')
        val parsed = if (parts.size == 2) {
            val n = parts[0].toFloatOrNull() ?: return 0f
            val d = parts[1].toFloatOrNull() ?: return 0f
            if (d == 0f) 0f else (n / d)
        } else v.toFloatOrNull() ?: 0f
        return parsed.coerceAtLeast(0f)
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        cursor.getLong(sizeIdx)
                    } else 0L
                    return name.ifBlank { uri.lastPathSegment.orEmpty() } to size
                }
            }
        }
        return uri.lastPathSegment.orEmpty() to 0L
    }

    // Format from the FILENAME EXTENSION first, MIME only as a fallback.
    //
    // Extension wins because MIME is unreliable on the way in: providers
    // routinely hand a RAW over as `application/octet-stream`, which is exactly
    // why the picker intent uses the any-type wildcard. The extension list
    // mirrors `sourceFormatOf()` in photo-editor.
    //
    // Line comments, not KDoc: a MIME wildcard pattern contains the character
    // pair that CLOSES a block comment, so writing one inside /** ... */ ends
    // the comment early and the rest of the file becomes garbage. See CLAUDE.md
    // hard rule 3 — the same trap in the opposite direction.
    fun formatOf(displayName: String, mimeType: String): PhotoFormat {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val byExt = when (ext) {
            "jpg", "jpeg", "jpe" -> PhotoFormat.Jpeg
            "webp" -> PhotoFormat.Webp
            "bmp" -> PhotoFormat.Bmp
            "png" -> PhotoFormat.Png
            "tif", "tiff" -> PhotoFormat.Tiff
            in RAW_EXTENSIONS -> PhotoFormat.Raw
            else -> PhotoFormat.Unknown
        }
        if (byExt != PhotoFormat.Unknown) return byExt

        val m = mimeType.lowercase()
        return when {
            m.contains("jpeg") -> PhotoFormat.Jpeg
            m.contains("webp") -> PhotoFormat.Webp
            m.contains("bmp") -> PhotoFormat.Bmp
            m.contains("png") -> PhotoFormat.Png
            m.contains("tiff") -> PhotoFormat.Tiff
            m.contains("raw") || m.contains("dng") || m.contains("-cr2") ||
                m.contains("-cr3") || m.contains("-nef") || m.contains("-arw") -> PhotoFormat.Raw
            else -> PhotoFormat.Unknown
        }
    }

    private fun parseExifDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value)?.time
        }.getOrNull()
    }

    /** LibRaw-supported RAW extensions, mirroring photo-editor's sourceFormatOf(). */
    private val RAW_EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "srf",
        "raf", "rw2", "raw", "orf", "pef", "srw", "x3f", "erf", "3fr",
        "fff", "dcr", "k25", "kdc", "mrw", "rwl", "mef", "iiq", "ari",
        "r3d", "gpr", "braw",
    )
}
