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
 */

package com.RAZStudio.StudioRoom.feature.photo_editor.raw.model

import com.RAZStudio.StudioRoom.core.domain.image.Metadata
import com.RAZStudio.StudioRoom.core.domain.image.metadataOf
import com.RAZStudio.StudioRoom.core.domain.image.model.MetadataTag

/**
 * Convert RAW-file metadata into the generic [Metadata] interface used by the
 * photo-editor EXIF editor.  Only fields that have non-empty / non-zero values
 * are added so the editor shows exactly what the camera recorded.
 */
fun RawMetadata.toMetadata(): Metadata {
    fun rational(n: Int, d: Int = 1): String = "$n/$d"

    val tags = mutableMapOf<MetadataTag, String>()

    fun put(tag: MetadataTag, value: String?) { if (!value.isNullOrEmpty()) tags[tag] = value }

    put(MetadataTag.Make,  cameraMake)
    put(MetadataTag.Model, cameraModel)
    put(MetadataTag.Software, softwareVersion)
    put(MetadataTag.LensModel, lensInfo)
    put(MetadataTag.LensMake,  lensMake)
    put(MetadataTag.Artist,    artist)
    put(MetadataTag.Copyright, copyright)
    put(MetadataTag.ImageDescription, imageDescription)
    put(MetadataTag.DatetimeOriginal,  dateTimeOriginal)
    put(MetadataTag.DatetimeDigitized, dateTimeDigitized)

    if (focalLength > 0f)     put(MetadataTag.FocalLength,           rational((focalLength * 100).toInt(), 100))
    if (focalLength35mm > 0f) put(MetadataTag.FocalLengthIn35mmFilm, "${focalLength35mm.toInt()}")
    if (aperture > 0f)        put(MetadataTag.FNumber,               rational((aperture * 100).toInt(), 100))
    if (iso > 0)              put(MetadataTag.PhotographicSensitivity, "$iso")
    if (exposureBias != 0f)   put(MetadataTag.ExposureBiasValue,     rational((exposureBias * 100).toInt(), 100))

    if (shutterSpeed > 0f) {
        val expTime = if (shutterSpeed >= 1f) {
            rational((shutterSpeed * 10).toInt(), 10)
        } else {
            rational(1, (1.0 / shutterSpeed).toInt().coerceAtLeast(1))
        }
        put(MetadataTag.ExposureTime, expTime)
    }

    gpsLatitude?.let { lat ->
        put(MetadataTag.GpsLatitudeRef, if (lat >= 0) "N" else "S")
        put(MetadataTag.GpsLatitude,    degreesToRational(kotlin.math.abs(lat)))
    }
    gpsLongitude?.let { lon ->
        put(MetadataTag.GpsLongitudeRef, if (lon >= 0) "E" else "W")
        put(MetadataTag.GpsLongitude,    degreesToRational(kotlin.math.abs(lon)))
    }
    gpsAltitude?.let { alt ->
        put(MetadataTag.GpsAltitudeRef, if (alt >= 0) "0" else "1")
        put(MetadataTag.GpsAltitude,    rational((kotlin.math.abs(alt) * 100).toInt(), 100))
    }
    if (gpsTimestamp.isNotEmpty()) put(MetadataTag.GpsTimestamp, gpsTimestamp)

    return metadataOf(tags)
}

private fun degreesToRational(deg: Double): String {
    val d = deg.toInt()
    val mFull = (deg - d) * 60.0
    val m = mFull.toInt()
    val s = (mFull - m) * 60.0
    return "$d/1,${m}/1,${(s * 1000).toInt()}/1000"
}
